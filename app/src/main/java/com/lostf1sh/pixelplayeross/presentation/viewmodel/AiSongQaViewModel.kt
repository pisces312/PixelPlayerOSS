/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, GPL-3.0-or-later.
 */
package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.ai.AiChatMessage
import com.lostf1sh.pixelplayeross.data.ai.AiHandler
import com.lostf1sh.pixelplayeross.data.ai.AiProgressEvent
import com.lostf1sh.pixelplayeross.data.ai.AiProgressListener
import com.lostf1sh.pixelplayeross.data.ai.SongQaContextBuilder
import com.lostf1sh.pixelplayeross.data.ai.provider.AiErrorKind
import com.lostf1sh.pixelplayeross.data.ai.provider.AiProviderException
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AiPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the player's current-song Q&A session.
 *
 * The conversation is deliberately in-memory: closing the sheet cancels any request but keeps the
 * transcript, while "new conversation" or switching the context song clears it. Nothing here is
 * written to Room or DataStore beyond the existing AI usage / request-log diagnostics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiSongQaViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val handler: AiHandler,
    private val preferences: AiPreferencesRepository,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiSongQaUiState())
    val uiState: StateFlow<AiSongQaUiState> = _uiState.asStateFlow()

    private val configuredFlow: StateFlow<Boolean> =
        preferences.providerFlow
            .flatMapLatest { provider ->
                if (provider.requiresApiKey) {
                    preferences.getApiKey(provider).map { it.isNotBlank() }
                } else {
                    preferences.getBaseUrl(provider).map { it.isNotBlank() }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var requestJob: Job? = null
    private var requestGeneration = 0
    private var storedLyrics: Lyrics? = null
    private var failedQuestion: String? = null

    init {
        viewModelScope.launch {
            configuredFlow.collect { configured ->
                _uiState.update { it.copy(isConfigured = configured) }
            }
        }
    }

    /**
     * Opens or re-opens the sheet. Reopening the same song keeps its in-memory transcript; opening
     * another song starts a clean session so one answer can never be attributed to two tracks.
     */
    fun openSession(song: Song) {
        val current = _uiState.value
        if (current.song?.id == song.id) {
            _uiState.update { it.copy(currentPlayerSongId = song.id) }
            return
        }

        requestGeneration++
        requestJob?.cancel()
        requestJob = null
        failedQuestion = null
        storedLyrics = null
        _uiState.update {
            AiSongQaUiState(
                song = song,
                currentPlayerSongId = song.id,
                isConfigured = it.isConfigured
            )
        }
        loadStoredLyrics(song)
    }

    /** Tracks the live player song without replacing the conversation's frozen context. */
    fun updateCurrentPlayerSong(song: Song?) {
        _uiState.update { it.copy(currentPlayerSongId = song?.id) }
    }

    /** Closing the sheet stops paying for tokens but keeps the in-memory transcript. */
    fun dismissSession() {
        requestJob?.cancel()
    }

    fun setIncludeSongContext(include: Boolean) {
        _uiState.update { it.copy(includeSongContext = include) }
    }

    fun setIncludeLyrics(include: Boolean) {
        _uiState.update {
            it.copy(includeLyrics = include && it.hasStoredLyrics)
        }
    }

    /** Draft text stays in memory so closing the sheet does not throw away a half-written question. */
    fun setDraft(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    /** Clears the transcript while keeping the same song snapshot and context switches. */
    fun startNewConversation() {
        cancelActiveRequest()
        failedQuestion = null
        _uiState.update {
            it.copy(
                messages = emptyList(),
                isSending = false,
                errorMessage = null,
                includeLyrics = false,
                draft = ""
            )
        }
    }

    /** Starts a clean session for the live current song after the user explicitly switches. */
    fun switchToCurrentSong(currentSong: Song?) {
        if (currentSong == null || currentSong.id == _uiState.value.song?.id) return
        openSession(currentSong)
    }

    fun sendMessage(question: String = "", includeLyrics: Boolean? = null) {
        val state = _uiState.value
        val text = question.ifBlank { state.draft }.trim()
        if (text.isEmpty() || state.isSending || !state.isConfigured) return
        val useLyrics = includeLyrics ?: state.includeLyrics
        if (includeLyrics != null) setIncludeLyrics(includeLyrics)
        failedQuestion = null
        _uiState.update { it.copy(draft = "") }
        startRequest(text, reuseLastUserMessage = false, includeLyrics = useLyrics)
    }

    /** Re-runs the failed question without duplicating its user bubble. */
    fun retryLastFailed() {
        val question = failedQuestion ?: return
        val state = _uiState.value
        val canReuseBubble = state.messages.lastOrNull()?.let {
            it.role == AiChatMessage.Role.USER && it.content == question
        } == true
        failedQuestion = null
        _uiState.update { it.copy(errorMessage = null) }
        startRequest(
            question = question,
            reuseLastUserMessage = canReuseBubble,
            includeLyrics = state.includeLyrics
        )
    }

    fun stopGeneration() {
        requestJob?.cancel()
    }

    private fun startRequest(
        question: String,
        reuseLastUserMessage: Boolean,
        includeLyrics: Boolean
    ) {
        val state = _uiState.value
        val song = state.song ?: return
        if (state.isSending) return

        val generation = ++requestGeneration
        val userMessage =
            if (reuseLastUserMessage) {
                state.messages.lastOrNull()
            } else {
                AiSongQaMessage(
                    id = UUID.randomUUID().toString(),
                    role = AiChatMessage.Role.USER,
                    content = question
                )
            }
        if (userMessage == null) return

        val assistantId = UUID.randomUUID().toString()
        val visibleMessages = state.messages
            .let { messages -> if (reuseLastUserMessage) messages else messages + userMessage }
            .plus(
                AiSongQaMessage(
                    id = assistantId,
                    role = AiChatMessage.Role.ASSISTANT,
                    content = "",
                    isStreaming = true
                )
            )
        _uiState.update {
            it.copy(messages = visibleMessages, isSending = true, errorMessage = null)
        }

        val lyrics = storedLyrics.takeIf { includeLyrics }
        val systemPrompt = SongQaContextBuilder.systemPrompt(
            song = song,
            lyrics = lyrics,
            includeSongContext = state.includeSongContext,
            appLanguageTag = Locale.getDefault().toLanguageTag()
        )
        val history = visibleMessages.map { message ->
            AiChatMessage(role = message.role, content = message.content)
        }
        val requestMessages = SongQaContextBuilder.requestMessages(systemPrompt, history)

        requestJob = viewModelScope.launch {
            try {
                val answer = handler.chat(
                    messages = requestMessages,
                    promptType = AiHandler.PROMPT_TYPE_SONG_QA,
                    listener = AiProgressListener { event ->
                        if (generation != requestGeneration) return@AiProgressListener
                        if (event is AiProgressEvent.Answer) {
                            updateAssistantMessage(assistantId, event.full, isStreaming = true)
                        }
                    }
                )
                if (generation == requestGeneration) {
                    updateAssistantMessage(assistantId, answer, isStreaming = false)
                }
            } catch (t: Throwable) {
                if (generation != requestGeneration) return@launch
                if (t is CancellationException) {
                    finishCancelledAssistant(assistantId)
                } else {
                    failedQuestion = question
                    _uiState.update { current ->
                        current.copy(
                            messages = current.messages.filterNot { it.id == assistantId },
                            errorMessage = errorText(t)
                        )
                    }
                }
            } finally {
                if (generation == requestGeneration) {
                    _uiState.update { it.copy(isSending = false) }
                }
            }
        }
    }

    private fun updateAssistantMessage(id: String, content: String, isStreaming: Boolean) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == id) {
                        message.copy(content = content, isStreaming = isStreaming)
                    } else {
                        message
                    }
                }
            )
        }
    }

    private fun finishCancelledAssistant(id: String) {
        _uiState.update { state ->
            val assistant = state.messages.firstOrNull { it.id == id }
            state.copy(
                messages =
                    if (assistant?.content.isNullOrBlank()) {
                        state.messages.filterNot { it.id == id }
                    } else {
                        state.messages.map { message ->
                            if (message.id == id) message.copy(isStreaming = false) else message
                        }
                    }
            )
        }
    }

    private fun cancelActiveRequest() {
        requestGeneration++
        requestJob?.cancel()
        requestJob = null
    }

    private fun loadStoredLyrics(song: Song) {
        viewModelScope.launch {
            val loaded = runCatching { lyricsRepository.getStoredLyrics(song)?.first }.getOrNull()
                ?: song.lyrics
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Lyrics(plain = it.lines().filter(String::isNotBlank)) }
            storedLyrics = loaded?.takeIf { SongQaContextBuilder.lyricsText(it).isNotBlank() }
            _uiState.update {
                if (it.song?.id == song.id) {
                    it.copy(hasStoredLyrics = storedLyrics != null)
                } else {
                    it
                }
            }
        }
    }

    private fun errorText(throwable: Throwable): String {
        val kind = (throwable as? AiProviderException)?.kind
        val resId = when (kind) {
            AiErrorKind.UNAUTHORIZED -> R.string.ai_error_unauthorized
            AiErrorKind.QUOTA_EXCEEDED -> R.string.ai_error_quota
            AiErrorKind.MODEL_NOT_FOUND -> R.string.ai_error_model_not_found
            AiErrorKind.RATE_LIMITED -> R.string.ai_error_rate_limited
            AiErrorKind.SERVER -> R.string.ai_error_server
            AiErrorKind.NETWORK -> R.string.ai_error_network
            AiErrorKind.RESPONSE_PARSE -> R.string.ai_error_parse
            else -> R.string.ai_error_unknown
        }
        val text = context.getString(resId)
        val detail = (throwable as? AiProviderException)?.message
        return if (detail.isNullOrBlank()) text else "$text\n$detail"
    }
}

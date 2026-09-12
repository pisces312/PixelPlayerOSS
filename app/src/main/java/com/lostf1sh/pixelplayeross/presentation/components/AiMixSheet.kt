package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.NlpPlaylistPreviewState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SerendipityUiState

/** Lengths offered as one-tap chips; they map straight to the generator's max length. */
private val AI_MIX_LENGTHS = listOf(15, 25, 40)

private val AI_MIX_IDEAS = listOf(
    R.string.ai_mix_idea_late_night,
    R.string.ai_mix_idea_commute,
    R.string.ai_mix_idea_workout,
    R.string.ai_mix_idea_focus,
    R.string.ai_mix_idea_cleaning,
    R.string.ai_mix_idea_sunday
)

/**
 * Home screen AI mix flow: describe a mood, get a list, then play it or just save it.
 *
 * Three phases live in one sheet — input, generating, result — so the prompt the user typed
 * stays on screen the whole time and "regenerate" is a single tap.
 *
 * The same sheet also serves Serendipity: passing a non-null [serendipity] swaps the input phase
 * for "signals we could read + the prompt they composed", and hides the sampling controls because
 * that path forces its own. Sharing the sheet keeps the result phase, the play/save actions and
 * the error handling identical between the two entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiMixSheet(
    state: NlpPlaylistPreviewState,
    sampleConfig: SampleConfig,
    onGenerate: (String, Int) -> Unit,
    onSave: (name: String, songs: List<Song>, prompt: String, startPlayback: Boolean) -> Unit,
    onDismiss: () -> Unit,
    serendipity: SerendipityUiState? = null,
    /** Localized labels for the signals that were actually read; empty means "nothing known". */
    serendipityChips: List<String> = emptyList(),
    /** Suggested playlist name built from the same signals ("Rain · Evening · 19:20"). */
    serendipityDefaultName: String? = null,
    onReshuffleSerendipity: (() -> Unit)? = null,
    onRephraseSerendipity: (() -> Unit)? = null
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var maxLength by rememberSaveable {
        mutableIntStateOf(PlaylistViewModel.DEFAULT_AI_MIX_LENGTH)
    }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    // Editable copy of the generated result: removing a song must not fight the view model state.
    val resultSongs = remember { mutableStateListOf<Song>() }
    var mixName by rememberSaveable { mutableStateOf("") }
    // Last name this sheet produced on its own: an untouched name is replaced on regenerate,
    // an edited one is left alone.
    var lastGeneratedName by rememberSaveable { mutableStateOf("") }

    val ideas = AI_MIX_IDEAS.map { stringResource(it) }

    // Serendipity composes the prompt before the sheet opens; the user can still edit it, and a
    // reshuffle or an AI rephrase replaces whatever is in the field.
    LaunchedEffect(serendipity?.prompt) {
        serendipity?.prompt?.takeIf { it.isNotBlank() }?.let { prompt = it }
    }

    LaunchedEffect(state) {
        if (state.hasResult && state.songs.isNotEmpty()) {
            resultSongs.clear()
            resultSongs.addAll(state.songs)
            val generated = serendipityDefaultName ?: aiMixDefaultName(prompt, ideas)
            if (mixName.isBlank() || mixName == lastGeneratedName) mixName = generated
            lastGeneratedName = generated
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text =
                        if (serendipity != null) stringResource(R.string.ai_serendipity_sheet_title)
                        else stringResource(R.string.ai_mix_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close)
                )
            }
        }

        AnimatedContent(
            targetState = when {
                state.isGenerating -> AiMixPhase.Generating
                state.hasResult -> AiMixPhase.Result
                else -> AiMixPhase.Input
            },
            label = "ai_mix_phase"
        ) { phase ->
            when (phase) {
                AiMixPhase.Input -> InputPhase(
                    prompt = prompt,
                    ideas = ideas,
                    onPromptChange = { prompt = it },
                    maxLength = maxLength,
                    onLengthChange = { maxLength = it },
                    advancedExpanded = advancedExpanded,
                    onAdvancedToggle = { advancedExpanded = it },
                    sampleConfig = sampleConfig,
                    onGenerate = { onGenerate(prompt.trim(), maxLength) },
                    serendipity = serendipity,
                    serendipityChips = serendipityChips,
                    onReshuffle = onReshuffleSerendipity,
                    onRephrase = onRephraseSerendipity
                )

                AiMixPhase.Generating -> GeneratingPhase()

                AiMixPhase.Result -> ResultPhase(
                    state = state,
                    songs = resultSongs,
                    onRemove = { resultSongs.remove(it) },
                    mixName = mixName,
                    onNameChange = { mixName = it },
                    onPlay = { onSave(mixName, resultSongs.toList(), prompt.trim(), true) },
                    onSaveOnly = { onSave(mixName, resultSongs.toList(), prompt.trim(), false) },
                    onRegenerate = { onGenerate(prompt.trim(), maxLength) }
                )
            }
        }
    }
}

private enum class AiMixPhase { Input, Generating, Result }

@Composable
private fun InputPhase(
    prompt: String,
    ideas: List<String>,
    onPromptChange: (String) -> Unit,
    maxLength: Int,
    onLengthChange: (Int) -> Unit,
    advancedExpanded: Boolean,
    onAdvancedToggle: (Boolean) -> Unit,
    sampleConfig: SampleConfig,
    onGenerate: () -> Unit,
    serendipity: SerendipityUiState?,
    serendipityChips: List<String>,
    onReshuffle: (() -> Unit)?,
    onRephrase: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (serendipity != null) {
            SerendipitySignals(
                state = serendipity,
                chips = serendipityChips,
                onReshuffle = onReshuffle,
                onRephrase = onRephrase
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = if (serendipity != null) 3 else 2,
            label =
                    if (serendipity != null) {
                        { Text(stringResource(R.string.ai_serendipity_prompt_label)) }
                    } else null,
            placeholder = { Text(stringResource(R.string.ai_mix_prompt_hint)) }
        )

        // The one-tap ideas belong to the describe flow: picking one would throw away the
        // composed context sentence.
        if (serendipity == null) {
            Text(
                text = stringResource(R.string.ai_mix_ideas_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ideas.forEach { idea ->
                    FilterChip(
                        selected = prompt.trim().equals(idea, ignoreCase = true),
                        onClick = { onPromptChange(idea) },
                        label = { Text(idea) }
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AI_MIX_LENGTHS.forEach { length ->
                FilterChip(
                    selected = maxLength == length,
                    onClick = { onLengthChange(length) },
                    label = { Text(length.toString()) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }
        }

        // Sampling is forced on the Serendipity path, so offering the controls there would lie.
        if (serendipity == null) {
            TextButton(onClick = { onAdvancedToggle(!advancedExpanded) }) {
                Text(stringResource(R.string.ai_mix_advanced))
            }
            if (advancedExpanded) {
                SampleModeDropdown(
                    modes = sampleConfig.modes,
                    selected = sampleConfig.mode,
                    enabled = true,
                    onSelect = sampleConfig.onModeChange
                )
                SampleSizeDropdown(
                    options = sampleConfig.sizes,
                    selected = sampleConfig.size,
                    enabled = true,
                    onSelect = sampleConfig.onSizeChange
                )
            }
        }

        Button(
            onClick = onGenerate,
            enabled = prompt.isNotBlank() && serendipity?.isCollecting != true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.ai_mix_generate))
        }
    }
}

/**
 * What Serendipity based the prompt on, plus the two ways to change the wording.
 *
 * The chips only ever show signals that were actually read — a missing weather line is the honest
 * answer to "why does this mix ignore the rain?", and it is the main reason this sheet exists
 * instead of generating straight away.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SerendipitySignals(
    state: SerendipityUiState,
    chips: List<String>,
    onReshuffle: (() -> Unit)?,
    onRephrase: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.isCollecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ai_serendipity_collecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (chips.isNotEmpty()) {
            Text(
                text = stringResource(R.string.ai_serendipity_signals_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chips.forEach { chip ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = chip,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onReshuffle != null) {
                TextButton(onClick = onReshuffle, enabled = !state.isCollecting) {
                    Text(stringResource(R.string.ai_serendipity_reshuffle))
                }
            }
            if (onRephrase != null) {
                TextButton(onClick = onRephrase, enabled = !state.isRephrasing && !state.isCollecting) {
                    Text(stringResource(R.string.ai_serendipity_rephrase))
                }
            }
            if (state.isRephrasing) {
                Spacer(modifier = Modifier.width(4.dp))
                LoadingIndicator(modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.ai_serendipity_rephrasing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.rephraseFailed) {
            Text(
                text = stringResource(R.string.ai_serendipity_rephrase_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GeneratingPhase() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LoadingIndicator(modifier = Modifier.size(56.dp))
            Text(
                text = stringResource(R.string.ai_mix_generating),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultPhase(
    state: NlpPlaylistPreviewState,
    songs: List<Song>,
    onRemove: (Song) -> Unit,
    mixName: String,
    onNameChange: (String) -> Unit,
    onPlay: () -> Unit,
    onSaveOnly: () -> Unit,
    onRegenerate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (songs.isNotEmpty()) {
            OutlinedTextField(
                value = mixName,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_playlist_name_label)) },
                singleLine = true
            )

            Text(
                text = stringResource(R.string.ai_mix_result_count, songs.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    MixSongRow(song = song, onRemove = { onRemove(song) })
                }
            }
        } else if (state.errorMessage == null) {
            Text(
                text = stringResource(R.string.presentation_batch_e_describe_no_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onSaveOnly, enabled = songs.isNotEmpty()) {
                Text(stringResource(R.string.ai_mix_save_only))
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onPlay, enabled = songs.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ai_mix_play))
            }
        }

        FilledTonalButton(
            onClick = onRegenerate,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.presentation_batch_e_describe_regenerate))
        }
    }
}

@Composable
private fun MixSongRow(song: Song, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SmartImage(
            model = song.albumArtUriString,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.ai_mix_remove_song)
            )
        }
    }
}

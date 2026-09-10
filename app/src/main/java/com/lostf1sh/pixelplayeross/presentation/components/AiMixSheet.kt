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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun defaultMixName(): String =
    "AI Mix · " + LocalDateTime.now().format(TIMESTAMP_FORMAT)

/**
 * Home screen AI mix flow: describe a mood, get a list, then play it or just save it.
 *
 * Three phases live in one sheet — input, generating, result — so the prompt the user typed
 * stays on screen the whole time and "regenerate" is a single tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiMixSheet(
    state: NlpPlaylistPreviewState,
    sampleConfig: SampleConfig,
    onGenerate: (String, Int) -> Unit,
    onSave: (name: String, songs: List<Song>, prompt: String, startPlayback: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var maxLength by rememberSaveable {
        mutableIntStateOf(PlaylistViewModel.DEFAULT_AI_MIX_LENGTH)
    }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    // Editable copy of the generated result: removing a song must not fight the view model state.
    val resultSongs = remember { mutableStateListOf<Song>() }
    var mixName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state.hasResult && state.songs.isNotEmpty()) {
            resultSongs.clear()
            resultSongs.addAll(state.songs)
            mixName = defaultMixName()
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
                text = stringResource(R.string.ai_mix_sheet_title),
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
                    onPromptChange = { prompt = it },
                    maxLength = maxLength,
                    onLengthChange = { maxLength = it },
                    advancedExpanded = advancedExpanded,
                    onAdvancedToggle = { advancedExpanded = it },
                    sampleConfig = sampleConfig,
                    onGenerate = { onGenerate(prompt.trim(), maxLength) }
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
    onPromptChange: (String) -> Unit,
    maxLength: Int,
    onLengthChange: (Int) -> Unit,
    advancedExpanded: Boolean,
    onAdvancedToggle: (Boolean) -> Unit,
    sampleConfig: SampleConfig,
    onGenerate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            placeholder = { Text(stringResource(R.string.ai_mix_prompt_hint)) }
        )

        Text(
            text = stringResource(R.string.ai_mix_ideas_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Resolved here, not inside onClick: a chip click handler is not a composable scope.
        val ideas = AI_MIX_IDEAS.map { stringResource(it) }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ideas.forEach { idea ->
                FilterChip(
                    selected = false,
                    onClick = { onPromptChange(idea) },
                    label = { Text(idea) }
                )
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

        Button(
            onClick = onGenerate,
            enabled = prompt.isNotBlank(),
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

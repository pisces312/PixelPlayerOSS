package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.ai.provider.AiProvider
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AiSettingsViewModel
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsSection(viewModel: AiSettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(status) {
        status?.let {
            Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatus()
        }
    }

    Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.ai_section_provider))
            ProviderDropdown(current = uiState.provider, onSelect = viewModel::setProvider)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.ai_section_credentials))
            OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = viewModel::setApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ai_api_key_label)) },
                    placeholder = { Text(stringResource(R.string.ai_api_key_placeholder)) },
                    singleLine = true,
                    visualTransformation =
                            if (uiState.apiKey.isBlank()) VisualTransformation.None
                            else PasswordVisualTransformation(),
                    supportingText =
                            if (!uiState.provider.hasConfigurableUrl && uiState.endpoint.isNotBlank()) {
                                { Text(stringResource(R.string.ai_endpoint_resolved, uiState.endpoint)) }
                            } else null
            )
            if (uiState.provider.hasConfigurableUrl) {
                OutlinedTextField(
                        value = uiState.baseUrl,
                        onValueChange = viewModel::setBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ai_base_url_label)) },
                        placeholder = { Text(stringResource(R.string.ai_base_url_placeholder)) },
                        singleLine = true
                )
            }
            if (uiState.availableModels.isEmpty()) {
                OutlinedTextField(
                        value = uiState.model,
                        onValueChange = viewModel::setModel,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ai_model_label)) },
                        placeholder = { Text(stringResource(R.string.ai_model_placeholder)) },
                        supportingText = { Text(stringResource(R.string.ai_model_no_list)) },
                        singleLine = true
                )
            } else {
                ModelDropdown(
                        model = uiState.model,
                        models = uiState.availableModels,
                        onSelect = viewModel::setModel
                )
            }
            SwitchSettingItem(
                    title = stringResource(R.string.ai_thinking_title),
                    subtitle = stringResource(R.string.ai_thinking_subtitle),
                    checked = uiState.thinkingEnabled,
                    onCheckedChange = viewModel::setThinkingEnabled,
                    enabled = uiState.provider.supportsThinkingParam,
                    leadingIcon = {
                        Icon(
                                Icons.Rounded.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                        )
                    }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.ai_section_actions))
            ActionRow(
                    label = stringResource(R.string.ai_model_fetch),
                    enabled = !uiState.modelsLoading,
                    onClick = viewModel::fetchModels
            )
            ActionRow(
                    label = stringResource(R.string.ai_action_test),
                    enabled = !uiState.testing,
                    onClick = viewModel::testConnection
            )
            ActionRow(
                    label = stringResource(R.string.ai_action_clear_cache),
                    enabled = true,
                    onClick = viewModel::clearCache
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.ai_section_usage))
            val total = usage.promptTokens + usage.outputTokens + usage.thoughtTokens
            Text(
                    text =
                            if (total == 0) stringResource(R.string.ai_usage_none)
                            else
                                    stringResource(
                                            R.string.ai_usage_summary,
                                            usage.promptTokens,
                                            usage.outputTokens,
                                            usage.thoughtTokens
                                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(current: AiProvider, onSelect: (AiProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
                value = current.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_provider_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AiProvider.entries.forEach { provider ->
                DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            onSelect(provider)
                            expanded = false
                        }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(model: String, models: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
                value = model,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_model_label)) },
                placeholder = { Text(stringResource(R.string.ai_model_pick)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { id ->
                DropdownMenuItem(
                        text = { Text(id) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        }
                )
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
    ) { Text(label) }
}

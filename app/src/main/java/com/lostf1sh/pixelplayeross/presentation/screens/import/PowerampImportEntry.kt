/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: import entry point written for this codebase.
 */
package com.lostf1sh.pixelplayeross.presentation.screens.import

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ImportViewModel

/**
 * 设置页入口：选文件 → 交给 [ImportViewModel] → 弹出导入向导。
 * 未完成的导入协程随 ViewModel 销毁自动取消。
 */
@Composable
fun PowerampImportEntry(
    modifier: Modifier = Modifier,
    viewModel: ImportViewModel = hiltViewModel()
) {
    var showWizard by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            showWizard = true
            viewModel.onFileSelected(uri)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    SettingsItem(
        title = stringResource(R.string.import_from_poweramp),
        subtitle = stringResource(R.string.import_from_poweramp_subtitle),
        leadingIcon = {
            Icon(Icons.Rounded.FileUpload, null, tint = MaterialTheme.colorScheme.secondary)
        },
        modifier = modifier,
        onClick = { filePicker.launch(arrayOf("*/*")) }
    )

    if (showWizard) {
        PowerampImportFlow(
            viewModel = viewModel,
            onDismiss = {
                showWizard = false
                viewModel.reset()
            }
        )
    }
}

package com.byokai.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.byokai.R
import com.byokai.core.common.Result
import com.byokai.data.export.ByokaiExportFormat
import com.byokai.data.export.ConversationExportManager
import com.byokai.domain.repository.ConversationRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export/import section for the settings screen (Milestone 4E, Phase 9B).
 *
 * - Export writes the versioned payload via SAF `CreateDocument`: a ZIP
 *   (`export.json` + `attachments/`) when the export carries images,
 *   readable by [ConversationExportManager.importFromBytes]; the
 *   filename carries the export date.
 * - Import reads via SAF `OpenDocument`, validates (size cap, ZIP magic
 *   or JSON, format id, version), and — when conversations already
 *   exist — asks for confirmation first, because re-importing the same
 *   file duplicates conversations (import always mints fresh ids).
 * - Outcomes surface through [onMessage] (snackbar); corrupt files
 *   produce a user-friendly error, never a crash or a log with
 *   conversation content.
 */
@Composable
fun DataPortabilitySection(
    exportManager: ConversationExportManager,
    conversationRepository: ConversationRepository,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingImportConfirm by remember { mutableStateOf(false) }

    // Resolved at composition (configuration-aware): the SAF callbacks
    // below are not composable scopes, so they cannot call
    // stringResource directly.
    val exportDoneText = stringResource(R.string.settings_export_done)
    val exportErrorText = stringResource(R.string.settings_export_error)
    val importDoneTemplate = stringResource(R.string.settings_import_done)
    val importReadErrorText = stringResource(R.string.settings_import_read_error)
    val importInvalidTemplate = stringResource(R.string.settings_import_invalid)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Phase 9B: the ZIP carries attachment files alongside
            // export.json; importFromBytes still accepts legacy v1 JSON.
            val result = exportManager.exportToZip()
            when (result) {
                is Result.Success -> {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                result.data.inputStream().use { it.copyTo(out) }
                            } ?: throw IllegalStateException("null stream")
                        }.isSuccess
                    }
                    runCatching { result.data.delete() }
                    onMessage(if (ok) exportDoneText else exportErrorText)
                }
                is Result.Error -> onMessage(exportErrorText)
                is Result.Loading -> Unit
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Read with the size cap enforced BEFORE parsing: the
            // byte count, not the parsed model, is the gate.
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(ByokaiExportFormat.MAX_BYTES + 1)
                        var total = 0
                        while (true) {
                            val read = input.read(
                                buffer,
                                total,
                                buffer.size - total,
                            )
                            if (read <= 0) break
                            total += read
                            if (total > ByokaiExportFormat.MAX_BYTES) break
                        }
                        buffer.copyOf(total)
                    }
                }.getOrNull()
            }
            if (bytes == null) {
                onMessage(importReadErrorText)
                return@launch
            }
            // Never log conversation content on failure — only the
            // user-friendly message below. importFromBytes sniffs the
            // ZIP magic and falls back to legacy v1 JSON.
            when (val result = exportManager.importFromBytes(bytes)) {
                is Result.Success -> {
                    val summary = result.data
                    onMessage(
                        importDoneTemplate.format(
                            summary.importedConversations,
                            summary.importedMessages,
                        ),
                    )
                }
                is Result.Error ->
                    onMessage(importInvalidTemplate.format(result.cause.message.orEmpty()))
                is Result.Loading -> Unit
            }
        }
    }

    NavRow(
        title = stringResource(R.string.settings_export),
        summary = stringResource(R.string.settings_export_summary),
        onClick = {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            exportLauncher.launch("byokai-export-$stamp.zip")
        },
    )
    NavRow(
        title = stringResource(R.string.settings_import),
        summary = stringResource(R.string.settings_import_summary),
        onClick = {
            scope.launch {
                val existing = conversationRepository.conversations().first()
                if (existing.isNotEmpty()) {
                    pendingImportConfirm = true
                } else {
                    // Accept both the Phase 9B ZIP and legacy v1 JSON.
                    importLauncher.launch(arrayOf("application/zip", "application/json"))
                }
            }
        },
    )

    if (pendingImportConfirm) {
        AlertDialog(
            onDismissRequest = { pendingImportConfirm = false },
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportConfirm = false
                        importLauncher.launch(arrayOf("application/zip", "application/json"))
                    },
                ) {
                    Text(stringResource(R.string.settings_import_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportConfirm = false }) {
                    Text(stringResource(R.string.prompts_cancel))
                }
            },
        )
    }
}

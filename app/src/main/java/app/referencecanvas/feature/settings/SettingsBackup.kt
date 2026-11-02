package app.referencecanvas.feature.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.R
import app.referencecanvas.core.backup.BackupFileInfo
import app.referencecanvas.core.backup.BackupOutcome
import app.referencecanvas.core.backup.BackupProgress
import app.referencecanvas.core.backup.BackupSchedule
import app.referencecanvas.core.backup.InvalidReason
import app.referencecanvas.core.backup.RestoreFailure
import app.referencecanvas.core.backup.RestoreState
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.ui.FileActions
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun BackupSection(vm: BackupViewModel) {
    val colors = RcTheme.colors
    val settings by vm.backup.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val destinationOk by vm.destinationOk.collectAsStateWithLifecycle()
    val backups by vm.backups.collectAsStateWithLifecycle()
    var chooseSource by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onFolderPicked(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.beginRestore(uri)
    }

    Text(stringResource(R.string.backup_explainer), color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.backup_same_device_warning), color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)

    Label(R.string.backup_folder)
    Text(
        settings.destinationName ?: stringResource(R.string.backup_folder_none),
        color = colors.textPrimary,
        style = MaterialTheme.typography.bodyLarge,
    )
    if (settings.destinationUri != null && destinationOk == false) {
        Text(stringResource(R.string.backup_folder_unavailable), color = colors.danger, style = MaterialTheme.typography.bodyMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { folderPicker.launch(null) }) {
            Text(stringResource(if (settings.destinationUri == null) R.string.backup_choose_folder else R.string.backup_change_folder))
        }
        if (settings.destinationUri != null) {
            TextButton(onClick = vm::clearDestination) { Text(stringResource(R.string.backup_forget_folder)) }
        }
    }

    Label(R.string.backup_schedule)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        BackupSchedule.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = settings.schedule == option,
                enabled = settings.destinationUri != null,
                onClick = { vm.setSchedule(option) },
                shape = SegmentedButtonDefaults.itemShape(index, BackupSchedule.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.accent.copy(alpha = 0.18f),
                    activeContentColor = colors.textPrimary,
                    activeBorderColor = colors.accent,
                ),
            ) {
                Text(
                    stringResource(
                        when (option) {
                            BackupSchedule.Off -> R.string.schedule_off
                            BackupSchedule.Daily -> R.string.schedule_daily
                            BackupSchedule.Weekly -> R.string.schedule_weekly
                        },
                    ),
                )
            }
        }
    }
    Hint(R.string.backup_schedule_hint)

    // The honest status line: only a verified backup is ever reported as a success.
    val success = settings.lastSuccessAt
    Text(
        if (success == null) {
            stringResource(R.string.backup_never)
        } else {
            stringResource(
                R.string.backup_last_success,
                DateUtils.getRelativeTimeSpanString(success).toString(),
                Formatter.formatShortFileSize(LocalContext.current, settings.lastBytes),
            )
        },
        color = colors.textPrimary,
        style = MaterialTheme.typography.bodyMedium,
    )
    val outcome = settings.lastOutcome
    if (outcome != null && outcome != BackupOutcome.Success) {
        Text(
            stringResource(R.string.backup_last_failed, stringResource(outcome.messageRes())),
            color = colors.danger,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    when (val p = progress) {
        is BackupProgress.Running -> {
            Text(stringResource(p.phase.labelRes()), color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            if (p.total > 0 && p.phase == BackupProgress.Phase.Files) {
                LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, color = colors.accent, trackColor = colors.border, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(color = colors.accent, trackColor = colors.border, modifier = Modifier.fillMaxWidth())
            }
            if (p.kind == BackupProgress.Kind.Backup) {
                OutlinedButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) }
            }
        }
        BackupProgress.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::backupNow,
                enabled = settings.destinationUri != null,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            ) { Text(stringResource(R.string.backup_now)) }
            OutlinedButton(onClick = { chooseSource = true }) { Text(stringResource(R.string.restore_from_backup)) }
        }
    }

    if (chooseSource) {
        RestoreSourceDialog(
            backups = backups,
            onPick = { vm.beginRestore(it.uri); chooseSource = false },
            onChooseFile = { chooseSource = false; filePicker.launch(arrayOf("*/*")) },
            onDismiss = { chooseSource = false },
        )
    }
    RestoreDialogs(vm)
}

@Composable
private fun RestoreSourceDialog(
    backups: List<BackupFileInfo>,
    onPick: (BackupFileInfo) -> Unit,
    onChooseFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_from_backup)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (backups.isEmpty()) {
                    Text(stringResource(R.string.restore_none_in_folder), color = RcTheme.colors.textSecondary)
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(backups, key = { it.uri.toString() }) { info ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(info) }.padding(vertical = 10.dp)) {
                                Text(info.name, color = RcTheme.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    Formatter.formatShortFileSize(context, info.sizeBytes),
                                    color = RcTheme.colors.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onChooseFile) { Text(stringResource(R.string.restore_choose_file)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Validation → explicit confirmation → restore → result. Nothing is replaced before the confirmation. */
@Composable
private fun RestoreDialogs(vm: BackupViewModel) {
    val state by vm.restoreState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = RcTheme.colors
    when (val s = state) {
        RestoreState.Idle -> Unit
        is RestoreState.Validating -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.restore_checking)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restore_checking_message, Formatter.formatShortFileSize(context, s.bytesRead)))
                    LinearProgressIndicator(color = colors.accent, trackColor = colors.border, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.cancel(); vm.dismissRestore() }) { Text(stringResource(R.string.cancel)) } },
        )
        is RestoreState.Invalid -> AlertDialog(
            onDismissRequest = vm::dismissRestore,
            title = { Text(stringResource(R.string.restore_invalid_title)) },
            text = { Text(stringResource(s.reason.messageRes())) },
            confirmButton = { TextButton(onClick = vm::dismissRestore) { Text(stringResource(R.string.ok)) } },
        )
        is RestoreState.Confirm -> {
            val m = s.manifest
            AlertDialog(
                onDismissRequest = vm::dismissRestore,
                title = { Text(stringResource(R.string.restore_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.restore_confirm_details, DateFormat.getDateTimeInstance().format(Date(m.createdAt)), m.appVersion))
                        Text(
                            stringResource(
                                R.string.restore_confirm_counts,
                                m.tableCounts["boards"] ?: 0,
                                m.tableCounts["reference_items"] ?: 0,
                                m.fileCount,
                                Formatter.formatShortFileSize(context, m.fileBytes),
                            ),
                        )
                        if (m.missingFiles > 0) {
                            Text(stringResource(R.string.restore_confirm_missing, m.missingFiles), color = colors.danger)
                        }
                        Text(stringResource(R.string.restore_confirm_warning), color = colors.danger)
                    }
                },
                confirmButton = { TextButton(onClick = vm::confirmRestore) { Text(stringResource(R.string.restore_replace), color = colors.danger) } },
                dismissButton = { TextButton(onClick = vm::dismissRestore) { Text(stringResource(R.string.cancel)) } },
            )
        }
        RestoreState.Restoring -> {
            val progress by vm.progress.collectAsStateWithLifecycle()
            val read = (progress as? BackupProgress.Running)?.done ?: 0L
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.restore_running)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.restore_running_message, Formatter.formatShortFileSize(context, read)))
                        LinearProgressIndicator(color = colors.accent, trackColor = colors.border, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {},
                // Safe to cancel: the database change is one transaction that simply rolls back.
                dismissButton = { TextButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is RestoreState.Done -> AlertDialog(
            onDismissRequest = vm::dismissRestore,
            title = { Text(stringResource(R.string.restore_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.restore_done_message, s.rows, s.files))
                    if (s.missingFiles > 0) Text(stringResource(R.string.restore_confirm_missing, s.missingFiles))
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissRestore) { Text(stringResource(R.string.ok)) } },
        )
        is RestoreState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissRestore,
            title = { Text(stringResource(R.string.restore_failed_title)) },
            text = { Text(stringResource(R.string.restore_failed_message, stringResource(s.reason.messageRes()))) },
            confirmButton = { TextButton(onClick = vm::dismissRestore) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
internal fun StorageSection(vm: BackupViewModel, onOpenTrash: () -> Unit) {
    val colors = RcTheme.colors
    val context = LocalContext.current
    val usage by vm.usage.collectAsStateWithLifecycle()
    val trashCount by vm.trashCount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    usage?.let { u ->
        Text(
            stringResource(R.string.storage_library, u.referenceCount, Formatter.formatShortFileSize(context, u.referenceBytes)),
            color = colors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.storage_cache, Formatter.formatShortFileSize(context, u.cacheBytes)), color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.storage_free, Formatter.formatShortFileSize(context, u.deviceFreeBytes)), color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
    }
    Hint(R.string.storage_limits)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            vm.clearCache { freed ->
                Toast.makeText(context, context.getString(R.string.cache_cleared, Formatter.formatShortFileSize(context, freed)), Toast.LENGTH_SHORT).show()
            }
        }) { Text(stringResource(R.string.clear_cache)) }
        OutlinedButton(onClick = {
            vm.cleanUnusedFiles { result ->
                val message = if (result == null) {
                    context.getString(R.string.cleanup_skipped)
                } else {
                    context.getString(R.string.cleanup_done, result.deletedFiles, Formatter.formatShortFileSize(context, result.freedBytes))
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }) { Text(stringResource(R.string.clean_unused_files)) }
    }
    OutlinedButton(onClick = onOpenTrash, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.trash_with_count, trashCount))
    }
}

@Composable
internal fun DiagnosticsSection(vm: BackupViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Hint(R.string.diagnostics_hint)
    OutlinedButton(onClick = {
        scope.launch {
            val file = vm.exportDiagnostics()
            if (file == null) Toast.makeText(context, R.string.diagnostics_failed, Toast.LENGTH_SHORT).show()
            else FileActions.share(context, file, "text/plain", context.getString(R.string.diagnostics_share_title))
        }
    }) { Text(stringResource(R.string.diagnostics_share)) }
}

private fun BackupOutcome.messageRes(): Int = when (this) {
    BackupOutcome.Success -> R.string.outcome_success
    BackupOutcome.NoDestination -> R.string.outcome_no_destination
    BackupOutcome.DestinationUnavailable -> R.string.outcome_destination_unavailable
    BackupOutcome.NoSpace -> R.string.outcome_no_space
    BackupOutcome.Io -> R.string.outcome_io
    BackupOutcome.VerificationFailed -> R.string.outcome_verification_failed
    BackupOutcome.Cancelled -> R.string.outcome_cancelled
    BackupOutcome.Database -> R.string.outcome_database
    BackupOutcome.Unknown -> R.string.outcome_unknown
}

private fun BackupProgress.Phase.labelRes(): Int = when (this) {
    BackupProgress.Phase.Preparing -> R.string.phase_preparing
    BackupProgress.Phase.Database -> R.string.phase_database
    BackupProgress.Phase.Files -> R.string.phase_files
    BackupProgress.Phase.Verifying -> R.string.phase_verifying
    BackupProgress.Phase.Pruning -> R.string.phase_pruning
    BackupProgress.Phase.Restoring -> R.string.phase_restoring
}

private fun InvalidReason.messageRes(): Int = when (this) {
    InvalidReason.NotABackup -> R.string.invalid_not_backup
    InvalidReason.Incomplete -> R.string.invalid_incomplete
    InvalidReason.Corrupt -> R.string.invalid_corrupt
    InvalidReason.UnsafeEntry -> R.string.invalid_unsafe
    InvalidReason.NewerFormat -> R.string.invalid_newer
    InvalidReason.NewerSchema -> R.string.invalid_newer
    InvalidReason.Mismatch -> R.string.invalid_corrupt
    InvalidReason.Unreadable -> R.string.invalid_unreadable
}

private fun RestoreFailure.messageRes(): Int = when (this) {
    RestoreFailure.Unreadable -> R.string.invalid_unreadable
    RestoreFailure.Io -> R.string.outcome_io
    RestoreFailure.NoSpace -> R.string.outcome_no_space
    RestoreFailure.Database -> R.string.outcome_database
    RestoreFailure.Mismatch -> R.string.invalid_corrupt
}

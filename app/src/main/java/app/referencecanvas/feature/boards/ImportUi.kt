package app.referencecanvas.feature.boards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.data.importer.ImportFailureReason
import app.referencecanvas.data.importer.ImportReport

@Composable
fun AddReferencesFab(
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onCamera: () -> Unit,
    onLink: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val colors = RcTheme.colors
    Box(modifier) {
        ExtendedFloatingActionButton(
            onClick = { open = true },
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.add_references)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_from_photos)) },
                leadingIcon = { Icon(Icons.Filled.Image, null) },
                onClick = { open = false; onPhotos() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_from_files)) },
                leadingIcon = { Icon(Icons.Filled.Folder, null) },
                onClick = { open = false; onFiles() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_from_camera)) },
                leadingIcon = { Icon(Icons.Filled.CameraAlt, null) },
                onClick = { open = false; onCamera() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_link)) },
                leadingIcon = { Icon(Icons.Filled.Link, null) },
                onClick = { open = false; onLink() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.paste)) },
                leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                onClick = { open = false; onPaste() },
            )
        }
    }
}

@Composable
fun ImportProgressCard(state: ImportUiState.Running, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RcTheme.colors
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text(
                    stringResource(R.string.import_running, minOf(state.done + 1, state.total), state.total),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            LinearProgressIndicator(
                progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                color = colors.accent,
                trackColor = colors.border,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ImportReportDialog(report: ImportReport, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_done_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.import_imported, report.imported, report.imported))
                if (report.failures.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.import_failed, report.failures.size, report.failures.size),
                        color = RcTheme.colors.danger,
                    )
                    // Cap the on-screen list; every failure is still retried.
                    report.failures.take(8).forEach {
                        Text(
                            stringResource(R.string.import_failure_item, it.name, stringResource(it.reason.messageRes())),
                            style = MaterialTheme.typography.bodySmall,
                            color = RcTheme.colors.textSecondary,
                        )
                    }
                    if (report.failures.size > 8) {
                        Text(
                            stringResource(R.string.import_more_failures, report.failures.size - 8),
                            style = MaterialTheme.typography.bodySmall,
                            color = RcTheme.colors.textSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            if (report.failures.isNotEmpty()) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.import_retry)) }
            }
        },
    )
}

private fun ImportFailureReason.messageRes(): Int = when (this) {
    ImportFailureReason.NoSpace -> R.string.import_fail_no_space
    ImportFailureReason.PermissionDenied -> R.string.import_fail_permission
    ImportFailureReason.NotFound -> R.string.import_fail_not_found
    ImportFailureReason.Unreadable -> R.string.import_fail_unreadable
    ImportFailureReason.Io -> R.string.import_fail_io
    ImportFailureReason.Database -> R.string.import_fail_db
    ImportFailureReason.Unknown -> R.string.import_fail_unknown
}

/** Asks for a web address. Only the text is stored; the app never fetches the page. */
@Composable
fun AddLinkDialog(onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; invalid = false },
                    singleLine = true,
                    isError = invalid,
                    label = { Text(stringResource(R.string.link_address)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = {
                        Text(stringResource(if (invalid) R.string.link_invalid else R.string.link_not_fetched))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSubmit(text)) onDismiss() else invalid = true }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

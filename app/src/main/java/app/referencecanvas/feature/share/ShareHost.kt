package app.referencecanvas.feature.share

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.BoardNames
import app.referencecanvas.domain.model.BoardSummary
import app.referencecanvas.feature.boards.ImportReportDialog

/** Shows the Share-sheet flow (choose board, progress, result) on top of whatever screen is current. */
@Composable
fun ShareHost(onOpenBoard: (String) -> Unit, viewModel: ShareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val boards by viewModel.boardList.collectAsStateWithLifecycle()
    val context = LocalContext.current

    when (val s = state) {
        ShareState.Idle -> Unit
        ShareState.Unsupported -> {
            LaunchedEffect(s) {
                Toast.makeText(context, R.string.share_nothing, Toast.LENGTH_SHORT).show()
                viewModel.dismiss()
            }
        }
        is ShareState.Choosing -> ShareTargetDialog(
            boards = boards,
            isLink = s.payload is SharedPayload.Link,
            onPick = { viewModel.importInto(it.board.id) },
            onCreate = viewModel::createBoardAndImport,
            onDismiss = viewModel::dismiss,
        )
        is ShareState.Importing -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.import_running, minOf(s.done + 1, s.total), s.total)) },
            text = {
                LinearProgressIndicator(
                    progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                    color = RcTheme.colors.accent,
                    trackColor = RcTheme.colors.border,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.cancel)) } },
        )
        is ShareState.Finished -> ImportReportDialog(
            report = s.report,
            onRetry = { viewModel.retryFailed(s) },
            onDismiss = {
                viewModel.dismiss()
                if (s.report.imported > 0) onOpenBoard(s.boardId)
            },
        )
        is ShareState.LinkAdded -> {
            LaunchedEffect(s) {
                Toast.makeText(context, R.string.link_added, Toast.LENGTH_SHORT).show()
                viewModel.dismiss()
                onOpenBoard(s.boardId)
            }
        }
    }
}

@Composable
private fun ShareTargetDialog(
    boards: List<BoardSummary>,
    isLink: Boolean,
    onPick: (BoardSummary) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isLink) R.string.share_add_link_to else R.string.share_add_to)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.new_board)) },
                        modifier = Modifier.weight(1f),
                    )
                    Button(enabled = BoardNames.normalize(newName) != null, onClick = { onCreate(newName) }) {
                        Text(stringResource(R.string.create))
                    }
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(boards, key = { it.board.id }) { summary ->
                        Text(
                            summary.board.name,
                            color = RcTheme.colors.textPrimary,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(summary) }.padding(vertical = 14.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

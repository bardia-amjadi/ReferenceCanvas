package app.referencecanvas.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.BoardSummary

@Composable
fun BoardPickerDialog(
    boards: List<BoardSummary>,
    onPick: (BoardSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_board)) },
        text = {
            if (boards.isEmpty()) {
                Text(stringResource(R.string.no_other_boards), color = RcTheme.colors.textSecondary)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(boards, key = { it.board.id }) { summary ->
                        Text(
                            text = summary.board.name,
                            color = RcTheme.colors.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(summary) }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

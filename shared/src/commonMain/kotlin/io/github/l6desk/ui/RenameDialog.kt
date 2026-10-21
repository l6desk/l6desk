package io.github.l6desk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Rename a channel (up to 12 characters, shown on its scribble strip). */
@Composable
fun RenameDialog(channel: Int, current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ModalPanel("NAME CHANNEL $channel", onDismiss, maxWidth = 420.dp) {
        DeskTextField(
            value = text,
            onValueChange = { text = it.take(12) },
            onDone = { onRename(text) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("rename-field"),
        )
        Hint("Up to 12 characters. Leave empty for \"CH $channel\".")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End)) {
            LampButton("CANCEL", false, { onDismiss() }, DeskColors.Ink, Modifier.width(90.dp).height(40.dp))
            LampButton("OK", false, { onRename(text) }, DeskColors.Green, Modifier.width(90.dp).height(40.dp).testTag("rename-ok"))
        }
    }
}

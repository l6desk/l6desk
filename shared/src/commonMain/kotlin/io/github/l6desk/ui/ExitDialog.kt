package io.github.l6desk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Asked when the app closes while connected to a Pi bridge that can power off (the "closing the
 * app shuts down the Pi bridge" option): shut the Pi down too, close and leave it on, or carry on.
 * A question rather than straight away, because a powered-off Pi needs its power replugging, and
 * it carries the desk and the music player. [poweringOff]: waiting for the Pi to acknowledge.
 */
@Composable
fun ExitDialog(
    bridgeName: String,
    musicPlaying: Boolean,
    poweringOff: Boolean,
    onPowerOff: () -> Unit,
    onCloseOnly: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalPanel("CLOSE L6DESK", { if (!poweringOff) onCancel() }, maxWidth = 520.dp, modifier = Modifier.testTag("exit-dialog")) {
        if (poweringOff) {
            Hint("Shutting down $bridgeName…", DeskColors.Amber)
            return@ModalPanel
        }
        Hint("Shut down the Pi bridge ($bridgeName) as well? To start it again, unplug its power and plug it back in.")
        if (musicPlaying) Hint("Music is playing: shutting down the Pi stops it.", DeskColors.Red)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LampButton("CANCEL", false, { onCancel() }, DeskColors.Ink, Modifier.weight(1f).height(48.dp).testTag("exit-cancel"))
            LampButton("CLOSE, LEAVE PI ON", false, { onCloseOnly() }, DeskColors.Ink, Modifier.weight(1.4f).height(48.dp).testTag("exit-close-only"))
            LampButton("SHUT DOWN PI & CLOSE", false, { onPowerOff() }, DeskColors.Amber, Modifier.weight(1.6f).height(48.dp).testTag("exit-power-off"))
        }
    }
}

package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.model.SceneSlot

/** What happened when a scene was recalled on the mixer itself. */
sealed interface SceneNotice {
    val slot: SceneSlot

    /** The mixer now plays its own scene; the desk still shows the previous mix. */
    data class OutOfStep(override val slot: SceneSlot) : SceneNotice

    /** "Link mixer scenes" is on: the app recalled and sent its own scene in that slot. */
    data class Linked(override val slot: SceneSlot) : SceneNotice
}

interface SceneNoticeActions {
    fun pushAll()
    fun dismissSceneNotice()
}

/** A banner under the top bar after a scene is recalled on the mixer. */
@Composable
fun SceneNoticeBanner(notice: SceneNotice, actions: SceneNoticeActions, modifier: Modifier = Modifier) {
    val outOfStep = notice is SceneNotice.OutOfStep
    Row(
        modifier.fillMaxWidth()
            .background(if (outOfStep) Color(0xFF4A3A10) else Color(0xFF1F3A24))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("scene-notice"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            if (outOfStep) {
                "Scene ${notice.slot.name} recalled on the L6max: the desk no longer matches the mixer."
            } else {
                "Scene ${notice.slot.name} pressed on the L6max: app scene ${notice.slot.name} recalled and sent."
            },
            style = TextStyle(color = if (outOfStep) DeskColors.Amber else DeskColors.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        if (outOfStep) {
            LampButton(
                "SEND DESK", true, { actions.pushAll() }, DeskColors.Amber,
                Modifier.width(110.dp).height(30.dp).testTag("scene-notice-send"),
                description = "Send the desk to the mixer",
            )
        }
        LampButton(
            "DISMISS", false, { actions.dismissSceneNotice() }, DeskColors.Ink,
            Modifier.width(90.dp).height(30.dp).testTag("scene-notice-dismiss"),
        )
    }
}

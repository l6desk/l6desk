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
import io.github.l6desk.net.BridgeInfo

/**
 * Something wrong with the bridge's computer (protocol v5), shown on the desk rather than just
 * logged, because each can spoil a set: under-voltage (dropped Wi-Fi, a glitching song, a reboot),
 * heat (the Pi slows itself down) or little space left for songs (uploads fail). [key] identifies
 * it for DISMISS: a dismissed warning stays hidden until it changes (another dip) or clears and
 * comes back. [severe]: red rather than amber.
 */
data class BridgeWarning(val key: String, val text: String, val severe: Boolean = false) {
    companion object {
        fun from(info: BridgeInfo?): List<BridgeWarning> {
            info ?: return emptyList()
            val pi = if (info.kind == BridgeInfo.KIND_PI) "The Pi" else "The bridge's computer"
            val count = info.underVoltageCount ?: 0
            return buildList {
                if (info.underVoltageNow == true) {
                    add(
                        BridgeWarning(
                            "under-voltage-${maxOf(count, 1)}",
                            "UNDER-VOLTAGE: $pi's power supply is too weak right now. The link or a song may drop out. " +
                                "Use a 5 V 2.5 A supply and a short, thick cable.",
                            severe = true,
                        ),
                    )
                } else if (count > 0) {
                    add(
                        BridgeWarning(
                            "under-voltage-$count",
                            "$pi's power supply dipped too low ${if (count == 1) "once" else "$count times"} since it started " +
                                "(under-voltage). Check the supply and its cable before the show.",
                        ),
                    )
                }
                val temperature = info.temperatureC
                if (temperature != null && temperature >= BridgeInfo.HOT_C) {
                    add(BridgeWarning("hot", "$pi is hot (${temperature.toInt()} °C) and slows itself down to cool off. Give it some air: out of the sun, away from amps."))
                }
                val free = info.musicFreeMb
                if (free != null && free < BridgeInfo.LOW_SPACE_MB) {
                    add(BridgeWarning("space", "$pi has only $free MB left for songs, so uploads may fail. Delete songs or playlists you don't need (PLAYER → EDIT)."))
                }
            }
        }
    }
}

/**
 * The bridge's warnings under the top bar, one row at a time so the faders keep their travel: the
 * most serious first (red while severe, amber otherwise), "1 of 3" when there are more; DISMISS
 * moves on to the next.
 */
@Composable
fun BridgeWarningsBanner(warnings: List<BridgeWarning>, onDismiss: (BridgeWarning) -> Unit, modifier: Modifier = Modifier) {
    val w = warnings.sortedByDescending { it.severe }.firstOrNull() ?: return
    Row(
        modifier.fillMaxWidth()
            .background(if (w.severe) Color(0xFF4A1414) else Color(0xFF4A3A10))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("bridge-warnings"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            w.text,
            style = TextStyle(color = if (w.severe) DeskColors.Red else DeskColors.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f).testTag("bridge-warning-text-${w.key}"),
        )
        if (warnings.size > 1) {
            BasicText("1 of ${warnings.size}", style = TextStyle(color = DeskColors.InkDim, fontSize = 11.sp), modifier = Modifier.testTag("bridge-warning-count"))
        }
        LampButton(
            "DISMISS", false, { onDismiss(w) }, DeskColors.Ink,
            Modifier.width(90.dp).height(30.dp).testTag("bridge-warning-dismiss-${w.key}"),
        )
    }
}

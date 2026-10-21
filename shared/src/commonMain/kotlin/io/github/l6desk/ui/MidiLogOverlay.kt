package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.midi.CcCodec
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.LoggingTransport
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.toHex
import io.github.l6desk.model.Profile
import kotlinx.coroutines.delay

/**
 * QA overlay (CLAUDE.md, Rules): the connection route, message rates, and the last 20 messages
 * in each direction, decoded with the current profile.
 */
@Composable
fun MidiLogOverlay(log: LoggingTransport, connection: ConnectionState, profile: Profile, modifier: Modifier = Modifier) {
    val entries by log.log.collectAsState()
    var rates by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(log) {
        while (true) {
            rates = log.rates()
            delay(500)
        }
    }
    val mono = TextStyle(color = Color(0xFF8FE39A), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.width(360.dp).background(Color(0xEE0B0C0D), shape).border(1.dp, Color.Black, shape).padding(10.dp).testTag("midi-log"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val route = when (connection) {
            is ConnectionState.Connected -> "${connection.device.kind}: ${connection.device.name}"
            is ConnectionState.Connecting -> "connecting…"
            is ConnectionState.Error -> "error: ${connection.reason}"
            ConnectionState.Disconnected -> "not connected"
        }
        BasicText("MIDI  $route", style = mono.copy(color = DeskColors.Amber))
        BasicText("out ${rates.first}/s   in ${rates.second}/s", style = mono.copy(color = DeskColors.InkDim))
        for (e in entries.takeLast(24)) {
            val label = CcCodec.decode(e.message, profile)?.let { "${it.control} ${it.value}" } ?: padLabel(e.message, profile)
            val bytes = e.message.toBytes().let { if (it.size > 8) it.take(8).toHex() + " …(${it.size})" else it.toHex() }
            Row {
                BasicText(if (e.outgoing) "→ " else "← ", style = mono.copy(color = if (e.outgoing) DeskColors.Amber else DeskColors.Green))
                BasicText("${formatTime(e.atMillis)} $bytes  $label", style = mono, maxLines = 1)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}.${(ms % 1000 / 100)}"
}

/** "PAD 2 on" for a sound pad's note on the profile's channel, else "". */
private fun padLabel(message: MidiMessage, profile: Profile): String {
    val (channel, note, on) = when (message) {
        is MidiMessage.NoteOn -> Triple(message.channel, message.note, true)
        is MidiMessage.NoteOff -> Triple(message.channel, message.note, false)
        else -> return ""
    }
    if (channel != profile.midiChannel) return ""
    return profile.ccMap.padFor(note)?.let { "PAD $it ${if (on) "on" else "off"}" } ?: ""
}

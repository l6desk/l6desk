package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import io.github.l6desk.net.BridgeAddress
import io.github.l6desk.net.BridgeProtocol
import io.github.l6desk.net.NetworkMidiTransport
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiDeviceInfo

/** Pick what to connect to. Opened by tapping the connection status in the top bar. */
@Composable
fun ConnectionPanel(
    devices: List<MidiDeviceInfo>,
    connection: ConnectionState,
    onSelect: (MidiDeviceInfo) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
    /** Null where bridges can't be used. */
    onAddBridge: ((BridgeAddress) -> Unit)? = null,
    onRemoveBridge: ((MidiDeviceInfo) -> Unit)? = null,
) {
    val connectedId = (connection as? ConnectionState.Connected)?.device?.id
    ModalPanel("CONNECTION", onDismiss, modifier = Modifier.testTag("connection-panel")) {
        BasicText(
            "Use the L6max Mixer Control port. The L6max's other two ports won't control the mixer.",
            style = TextStyle(color = DeskColors.InkDim, fontSize = 12.sp),
        )
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (devices.isEmpty()) {
                BasicText("No MIDI devices found. Is the L6max on and plugged in?", style = TextStyle(color = DeskColors.Ink, fontSize = 13.sp))
            }
            for (d in devices) DeviceRow(d, d.id == connectedId, onSelect, onRemoveBridge.takeIf { d.kind == MidiDeviceInfo.Kind.NETWORK && !d.isFoundBridge })
        }
        if (onAddBridge != null) AddBridgeRow(onAddBridge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (connection is ConnectionState.Error) {
                BasicText(connection.reason, style = TextStyle(color = DeskColors.Red, fontSize = 12.sp), modifier = Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f))
            }
            LampButton("DISCONNECT", false, { onDisconnect() }, DeskColors.Red, Modifier.width(120.dp).height(40.dp).testTag("disconnect"))
            LampButton("DONE", false, { onDismiss() }, DeskColors.Ink, Modifier.width(90.dp).height(40.dp).testTag("connection-done"))
        }
    }
}

@Composable
private fun AddBridgeRow(onAdd: (BridgeAddress) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = BridgeAddress.parse(text)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DeskTextField(text, { text = it.take(60) }, onDone = { parsed?.let(onAdd); text = "" }, modifier = Modifier.weight(1f).testTag("bridge-address"))
        LampButton(
            "ADD BRIDGE", false, { parsed?.let(onAdd); text = "" }, DeskColors.Green,
            Modifier.width(120.dp).height(40.dp).alpha(if (parsed != null) 1f else 0.4f).testTag("bridge-add"),
        )
    }
    Hint(
        if (text.isNotBlank() && parsed == null) "Enter an IP address, optionally with :port (default ${BridgeProtocol.DEFAULT_PORT})."
        else "Bridges on this Wi-Fi network appear above by themselves. Otherwise enter one's IP address, e.g. 192.168.1.20 (the Pi's own hotspot: 10.42.0.1).",
    )
}

@Composable
private fun DeviceRow(device: MidiDeviceInfo, connected: Boolean, onSelect: (MidiDeviceInfo) -> Unit, onRemove: ((MidiDeviceInfo) -> Unit)? = null) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (connected) Color(0xFF203325) else DeskColors.ButtonBottom, shape)
            .border(1.dp, if (connected) DeskColors.Green else Color.Black, shape)
            .clickable { onSelect(device) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("device-${device.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(9.dp).background(if (connected) DeskColors.Green else Color(0xFF444444), CircleShape))
        Column(Modifier.weight(1f)) {
            BasicText(device.name, style = TextStyle(color = DeskColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            val kind = when (device.kind) {
                MidiDeviceInfo.Kind.USB -> "USB"
                MidiDeviceInfo.Kind.BLUETOOTH -> "Bluetooth"
                MidiDeviceInfo.Kind.NETWORK -> if (device.found) "Wi-Fi bridge, found on this network" else "Wi-Fi bridge"
                MidiDeviceInfo.Kind.SIMULATOR -> "Simulator — no mixer needed"
            }
            BasicText(kind, style = TextStyle(color = DeskColors.InkDim, fontSize = 11.sp))
        }
        when {
            device.isL6maxMixerControl -> Tag("MIXER CONTROL", DeskColors.Green)
            device.l6maxPort != null -> Tag("WRONG PORT", DeskColors.InkDim)
        }
        if (onRemove != null) {
            LampButton("REMOVE", false, { onRemove(device) }, DeskColors.Red, Modifier.width(80.dp).height(32.dp).testTag("remove-${device.id}"), fontSize = 9.sp)
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) = BasicText(
    text,
    style = TextStyle(color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    modifier = Modifier.border(1.dp, color, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
)

private val MidiDeviceInfo.isFoundBridge get() = id.startsWith(NetworkMidiTransport.FOUND_PREFIX)

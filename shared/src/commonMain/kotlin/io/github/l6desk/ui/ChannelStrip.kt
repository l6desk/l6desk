package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.ChannelState

/** Everything a strip can ask the desk to do. */
interface StripActions {
    fun setParam(channel: Int, param: ChannelParam, value: Int)
    fun setMute(channel: Int, on: Boolean)
    fun setSolo(channel: Int, on: Boolean)
    fun rename(channel: Int)

    /** A finger (or mouse) went down on, or lifted off, a knob or fader. */
    fun setTouching(channel: Int, param: ChannelParam, touching: Boolean)

    /** SENDS view: make [param] the send the faders set. */
    fun selectSend(param: ChannelParam)
}

private data class KnobSpec(val param: ChannelParam, val label: String, val color: Color, val bipolar: Boolean = true)

private val HI = KnobSpec(ChannelParam.EQ_HIGH, "HI", DeskColors.KnobRed)
private val FREQ = KnobSpec(ChannelParam.EQ_MID_FREQ, "FREQ", DeskColors.KnobGreenLight, bipolar = false)
private val MID = KnobSpec(ChannelParam.EQ_MID, "MID", DeskColors.KnobGreen)
private val LO = KnobSpec(ChannelParam.EQ_LOW, "LO", DeskColors.KnobBlue)

@Composable
fun ChannelStrip(
    channel: ChannelState,
    impliedMute: Boolean,
    actions: StripActions,
    modifier: Modifier = Modifier,
    view: DeskView = DeskView.MAIN,
    selectedSend: ChannelParam = ChannelParam.AUX1,
) {
    val m = LocalDeskMetrics.current
    val n = channel.number
    val stereo = n >= 5

    @Composable
    fun knob(spec: KnobSpec) = RotaryKnob(
        value = channel[spec.param],
        onValueChange = { actions.setParam(n, spec.param, it) },
        label = spec.label,
        color = spec.color,
        bipolar = spec.bipolar,
        default = spec.param.default,
        size = m.knob,
        onTouchChange = { actions.setTouching(n, spec.param, it) },
        modifier = Modifier.testTag("ch$n-${spec.param.name.lowercase()}"),
    )

    Column(
        modifier
            .fillMaxHeight()
            .background(if (stereo) DeskColors.PanelStereo else DeskColors.Panel)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StripHeader(channel, actions)

        if (view == DeskView.SENDS) {
            SendOverview(channel, selectedSend, actions::selectSend)
        } else {
            // EQ: HI, then the swept-mid pair (gain and frequency), then LO.
            SectionLabel("EQ")
            knob(HI)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                knob(MID)
                knob(FREQ)
            }
            knob(LO)
            Rule()

            RotaryKnob(
                value = channel.pan,
                onValueChange = { actions.setParam(n, ChannelParam.PAN, it) },
                label = "PAN",
                color = DeskColors.KnobGrey,
                default = ChannelParam.PAN.default,
                size = m.knob,
                describe = { v -> if (v == 64) "Centre" else if (v < 64) "Left ${64 - v}" else "Right ${v - 64}" },
                onTouchChange = { actions.setTouching(n, ChannelParam.PAN, it) },
                modifier = Modifier.testTag("ch$n-pan"),
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LampButton(
                "SOLO", channel.solo, { actions.setSolo(n, it) }, DeskColors.Amber,
                Modifier.weight(1f).height(m.lamp).testTag("ch$n-solo"), description = "Solo channel $n",
            )
            LampButton(
                "MUTE", channel.mute, { actions.setMute(n, it) }, DeskColors.Red,
                Modifier.weight(1f).height(m.lamp).testTag("ch$n-mute"), implied = impliedMute, description = "Mute channel $n",
            )
        }

        if (view == DeskView.SENDS) {
            val sendValue = channel.send(selectedSend)
            Fader(
                value = sendValue ?: 0,
                onValueChange = { actions.setParam(n, selectedSend, it) },
                label = "Channel $n ${sendLabel(selectedSend)}",
                default = 0,
                capHeight = m.faderCap,
                onTouchChange = { actions.setTouching(n, selectedSend, it) },
                capColor = sendColor(selectedSend),
                unknown = sendValue == null,
                modifier = Modifier.weight(1f).testTag("ch$n-send"),
            )
        } else {
            Fader(
                value = channel.level,
                onValueChange = { actions.setParam(n, ChannelParam.LEVEL, it) },
                label = "Channel $n level",
                default = ChannelParam.LEVEL.default,
                capHeight = m.faderCap,
                onTouchChange = { actions.setTouching(n, ChannelParam.LEVEL, it) },
                modifier = Modifier.weight(1f).testTag("ch$n-level"),
            )
        }

        Scribble(channel.name, Modifier.testTag("ch$n-name").clickable { actions.rename(n) })
    }
}

@Composable
private fun StripHeader(channel: ChannelState, actions: StripActions) {
    val n = channel.number
    Row(
        Modifier.fillMaxWidth().height(26.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("$n", style = TextStyle(color = Color(0xFFF0EDE6), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold))
        Spacer(Modifier.width(6.dp))
        if (n == 5 || n == 6) {
            // MONO ×2: the two inputs still share one level, pan and EQ (CLAUDE.md).
            LampButton(
                if (channel.monoX2) "MONO×2" else "ST", channel.monoX2,
                { actions.setParam(n, ChannelParam.MONO_X2, if (it) 127 else 0) },
                DeskColors.Green,
                Modifier.width(52.dp).height(22.dp).testTag("ch$n-mono"),
                description = "Mono ×2 channel $n", fontSize = 8.sp,
            )
        } else if (n == 7 || n == 8) {
            // USB input: CH7 plays USB 1/2, CH8 USB 3/4 (from the bridge's music player, or a
            // computer on USB). Unknown until set or pressed on the mixer: it can't be read.
            val usb = channel.usbInput
            LampButton(
                when (usb) {
                    true -> if (n == 7) "USB 1/2" else "USB 3/4"
                    false -> "ST"
                    null -> "USB ?"
                },
                usb == true,
                { actions.setParam(n, ChannelParam.USB_INPUT, if (it) 127 else 0) },
                DeskColors.Pad,
                Modifier.width(52.dp).height(22.dp).testTag("ch$n-usb"),
                description = "USB input channel $n", fontSize = 8.sp,
            )
        } else {
            BasicText(
                if (n >= 5) "ST" else "MONO",
                style = TextStyle(color = DeskColors.InkDim, fontSize = 8.sp, letterSpacing = 1.5.sp),
            )
        }
    }
    Rule()
}

@Composable
internal fun SectionLabel(text: String) = BasicText(
    text,
    style = TextStyle(color = DeskColors.InkDim, fontSize = 8.sp, letterSpacing = 3.sp, textAlign = TextAlign.Center),
)

@Composable
internal fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.4f)))

@Composable
internal fun Scribble(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier.fillMaxWidth().background(DeskColors.Scribble, shape).border(1.dp, Color.Black, shape).padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = DeskColors.ScribbleInk, fontSize = 10.sp, fontWeight = FontWeight.Bold),
        )
    }
}

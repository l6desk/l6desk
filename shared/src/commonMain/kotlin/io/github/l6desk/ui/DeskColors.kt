package io.github.l6desk.ui

import androidx.compose.ui.graphics.Color

/** Analogue-desk palette (dark by default for dim venues), matching the web proof of concept. */
object DeskColors {
    val Background = Color(0xFF111214)
    val Panel = Color(0xFF2A2C30)
    val PanelStereo = Color(0xFF2D2E31)
    val PanelMaster = Color(0xFF313338)
    val Divider = Color(0xFF121314)
    val Bridge = Color(0xFF1C1D20)
    val Ink = Color(0xFFD9D6CF)
    val InkDim = Color(0xFF8E8B84)
    val Tick = Color(0xFFA9A59C)
    val Slot = Color(0xFF050506)
    val Skirt = Color(0xFF1B1C1F)
    val Wood = Color(0xFF5A3219)
    val WoodDark = Color(0xFF3E2210)
    val Scribble = Color(0xFFECE7D6)
    val ScribbleInk = Color(0xFF1D1D1B)

    val Red = Color(0xFFE2483D)
    val Amber = Color(0xFFF2B632)
    val Green = Color(0xFF5CCF6A)

    val KnobRed = Color(0xFFC9473F)
    val KnobGreen = Color(0xFF3F9A5C)
    val KnobGreenLight = Color(0xFF7CC08D)
    val KnobBlue = Color(0xFF3C74C4)
    val KnobGrey = Color(0xFFC9C5BC)

    // One colour per send, used for its toggle, overview bar and fader caps in the SENDS view.
    val SendAux1 = Color(0xFF4A8FE0)
    val SendAux2 = Color(0xFF5CCF6A)
    val SendSubMix = Color(0xFFF2B632)
    val SendEfx = Color(0xFFB07EE8)

    // Sound pads (teal: not used by any send or lamp)
    val Pad = Color(0xFF3FC6D0)

    val ButtonTop = Color(0xFF3B3D42)
    val ButtonBottom = Color(0xFF26282B)
}

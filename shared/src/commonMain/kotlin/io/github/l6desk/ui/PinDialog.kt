package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.net.BridgePin

/**
 * A bridge asks for its PIN (protocol v6). [wrong]: the last one tried was refused. OK gives the
 * PIN (the app remembers it for this bridge); CANCEL stops connecting.
 */
@Composable
fun PinDialog(bridge: String, wrong: Boolean, onPin: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun ok() {
        if (BridgePin.isValid(text)) onPin(text)
    }
    ModalPanel("BRIDGE PIN", onCancel, maxWidth = 420.dp, modifier = Modifier.testTag("pin-dialog")) {
        Hint("$bridge asks for its PIN. The app remembers it for this bridge.")
        if (wrong) Hint("That PIN was wrong. Try again.", DeskColors.Red, Modifier.testTag("pin-wrong"))
        val shape = RoundedCornerShape(4.dp)
        BasicTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() }.take(8) },
            singleLine = true,
            textStyle = TextStyle(color = DeskColors.Ink, fontSize = 22.sp, letterSpacing = 6.sp),
            cursorBrush = SolidColor(DeskColors.Amber),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { ok() }),
            modifier = Modifier.fillMaxWidth().background(Color(0xFF15161A), shape).border(1.dp, Color(0xFF3A3D42), shape)
                .padding(12.dp).focusRequester(focus).testTag("pin-field"),
        )
        Hint("4 to 8 digits. It's set on the Pi (pin= in /etc/l6desk-bridge.conf), or in SETUP on a computer in Bridge mode.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            LampButton("CANCEL", false, { onCancel() }, DeskColors.Ink, Modifier.width(100.dp).height(44.dp).testTag("pin-cancel"))
            LampButton("OK", false, { ok() }, DeskColors.Green, Modifier.width(100.dp).height(44.dp).testTag("pin-ok"))
        }
    }
}

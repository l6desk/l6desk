package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A dimmed full-screen scrim with a centred panel; tapping the scrim calls [onDismiss]. */
@Composable
fun ModalPanel(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 560.dp,
    /** Shown at the right of the title row (e.g. a DONE button), saving a row of height. */
    titleTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        // Tap handlers via pointerInput, not clickable: clickable would merge the panel's
        // contents into one accessibility node, hiding its individual buttons.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(10.dp)
        Column(
            modifier
                .padding(vertical = 16.dp) // keeps the panel off the screen edges; content scrolls instead
                .widthIn(max = maxWidth)
                .fillMaxWidth(0.94f)
                .background(Color(0xFF222427), shape)
                .border(1.dp, Color.Black, shape)
                .pointerInput(Unit) { detectTapGestures { } } // taps inside don't reach the scrim
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    title,
                    style = TextStyle(color = DeskColors.Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                    modifier = Modifier.weight(1f),
                )
                titleTrailing?.invoke()
            }
            content()
        }
    }
}

/** A single-line text field in desk style. [onDone] runs on Enter / the keyboard's Done key. */
@Composable
fun DeskTextField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = DeskColors.Ink, fontSize = 16.sp),
        cursorBrush = SolidColor(DeskColors.Amber),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.background(Color(0xFF15161A), shape).border(1.dp, Color(0xFF3A3D42), shape).padding(10.dp),
    )
}

@Composable
internal fun SectionTitle(text: String) =
    BasicText(text, style = TextStyle(color = DeskColors.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp))

@Composable
internal fun Hint(text: String, color: Color = DeskColors.InkDim, modifier: Modifier = Modifier) =
    BasicText(text, style = TextStyle(color = color, fontSize = 11.sp), modifier = modifier)

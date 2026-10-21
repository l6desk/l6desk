package io.github.l6desk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.report.ProblemReport

/** Where a report is in [ReportDialog]: being written, being saved, or saved ([note] says where). */
sealed interface ReportStage {
    data object Writing : ReportStage
    data object Saving : ReportStage
    data class Saved(val note: String, val browserFailed: Boolean = false) : ReportStage
    data class Failed(val reason: String) : ReportStage
}

/**
 * REPORT: the user describes the problem; the report (their message, the screenshot taken as
 * REPORT was pressed, the connection and versions, recent events) is saved as a signed zip to
 * attach to a GitHub issue. [details] is report.json as it stands, shown on request, so the user
 * can see everything that goes in.
 */
@Composable
fun ReportDialog(
    message: String,
    onMessageChange: (String) -> Unit,
    screenshot: ImageBitmap?,
    includeScreenshot: Boolean,
    onIncludeScreenshot: (Boolean) -> Unit,
    showDetails: Boolean,
    onShowDetails: (Boolean) -> Unit,
    details: () -> String,
    install: String,
    stage: ReportStage,
    onSave: () -> Unit,
    onShare: (() -> Unit)?,
    onOpenGitHub: () -> Unit,
    onDismiss: () -> Unit,
) {
    val writing = stage is ReportStage.Writing || stage is ReportStage.Failed
    ModalPanel(
        "REPORT A PROBLEM", { if (stage != ReportStage.Saving) onDismiss() }, maxWidth = 680.dp, modifier = Modifier.testTag("report-dialog"),
        titleTrailing = { Hint("Install $install", modifier = Modifier.testTag("report-install")) },
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Hint("What happened, and what did you expect? The report is saved as a zip file, to attach to an issue on GitHub.")
            MessageField(message, onMessageChange, enabled = writing)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (screenshot != null) {
                    val shape = RoundedCornerShape(4.dp)
                    Image(
                        screenshot, "Screenshot",
                        Modifier.height(84.dp).border(1.dp, if (includeScreenshot) DeskColors.InkDim else Color(0xFF3A3D42), shape)
                            .let { if (includeScreenshot) it else it.background(Color.Black) }.testTag("report-thumbnail"),
                        alpha = if (includeScreenshot) 1f else 0.25f,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (screenshot != null) {
                        LampButton(
                            "INCLUDE SCREENSHOT", includeScreenshot, { if (writing) onIncludeScreenshot(it) }, DeskColors.Green,
                            Modifier.width(190.dp).height(40.dp).testTag("report-screenshot"),
                        )
                        Hint("It shows your channel names, and song titles if the player is open.")
                    } else {
                        Hint("No screenshot could be taken.")
                    }
                    LampButton(
                        "WHAT'S INCLUDED", showDetails, { onShowDetails(it) }, DeskColors.Ink,
                        Modifier.width(190.dp).height(40.dp).testTag("report-details-toggle"),
                    )
                }
            }
            if (showDetails) {
                Hint(
                    "Also included: the last 200 app events (connections, errors) and the last 20 MIDI messages each way. " +
                        "Home network addresses are left out.",
                )
                BasicText(
                    details(),
                    style = TextStyle(color = DeskColors.InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).background(Color(0xFF15161A)).padding(8.dp)
                        .verticalScroll(rememberScrollState()).testTag("report-details"),
                )
            }
            Hint("GitHub issues are public: anyone can read the report. Don't include anything private.", DeskColors.Amber)
            when (stage) {
                is ReportStage.Saved -> {
                    Hint(
                        "${stage.note} Now open a GitHub issue (it's filled in for you) and drag the file onto it. " +
                            "You'll need a free GitHub account.",
                        DeskColors.Green, Modifier.testTag("report-saved"),
                    )
                    if (stage.browserFailed) {
                        Hint("The browser couldn't be opened. Go to github.com/l6desk/l6desk/issues, choose New issue, and attach the file.", DeskColors.Red)
                    }
                }
                is ReportStage.Failed -> Hint(stage.reason, DeskColors.Red, Modifier.testTag("report-error"))
                ReportStage.Saving -> Hint("Saving…")
                ReportStage.Writing -> Unit
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            if (stage is ReportStage.Saved) {
                LampButton("DONE", false, { onDismiss() }, DeskColors.Ink, Modifier.width(100.dp).height(48.dp).testTag("report-done"))
                if (onShare != null) {
                    LampButton("SHARE", false, { onShare() }, DeskColors.Ink, Modifier.width(100.dp).height(48.dp).testTag("report-share"))
                }
                LampButton("OPEN GITHUB ISSUE", false, { onOpenGitHub() }, DeskColors.Amber, Modifier.width(200.dp).height(48.dp).testTag("report-github"))
            } else {
                LampButton("CANCEL", false, { if (writing) onDismiss() }, DeskColors.Ink, Modifier.width(100.dp).height(48.dp).testTag("report-cancel"))
                if (onShare != null) {
                    LampButton("SHARE", false, { if (writing) onShare() }, DeskColors.Ink, Modifier.width(100.dp).height(48.dp).testTag("report-share"))
                }
                LampButton("SAVE REPORT", false, { if (writing) onSave() }, DeskColors.Green, Modifier.width(160.dp).height(48.dp).testTag("report-save"))
            }
        }
    }
}

@Composable
private fun MessageField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(ProblemReport.MAX_MESSAGE)) },
        enabled = enabled,
        minLines = 4,
        maxLines = 8,
        textStyle = TextStyle(color = DeskColors.Ink, fontSize = 15.sp),
        cursorBrush = SolidColor(DeskColors.Amber),
        modifier = Modifier.fillMaxWidth().background(Color(0xFF15161A), shape).border(1.dp, Color(0xFF3A3D42), shape)
            .padding(10.dp).testTag("report-message"),
    )
}

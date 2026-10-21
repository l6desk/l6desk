package io.github.l6desk.report

/**
 * A new GitHub issue for a report, filled in through the URL: a title with the report's id, and a
 * short body (GitHub drops URLs much over 8,000 characters). The zip is attached by the user,
 * dragged onto the page.
 */
object ReportIssue {
    const val NEW_ISSUE = "https://github.com/l6desk/l6desk/issues/new"
    private const val MAX_BODY_MESSAGE = 1_500

    fun title(report: ProblemReport): String {
        val first = report.message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val summary = first?.let { if (it.length > 60) it.take(59).trimEnd() + "…" else it } ?: "Problem report"
        return "[report ${report.id}] $summary"
    }

    fun body(report: ProblemReport): String = buildString {
        val message = report.message.trim()
        appendLine("**What happened**")
        appendLine()
        appendLine(if (message.length > MAX_BODY_MESSAGE) message.take(MAX_BODY_MESSAGE) + " … (the rest is in the report)" else message.ifEmpty { "(no message)" })
        appendLine()
        appendLine("**Report**")
        appendLine()
        val p = report.platform
        val platform = listOfNotNull(p["os"], p["osVersion"], p["device"] ?: p["distribution"]?.let { "($it)" }).joinToString(" ")
        val bridge = report.bridge?.let { ", bridge \"${it.name}\" (protocol v${it.protocol})" }.orEmpty()
        appendLine("- Report `${report.id}`, install `${report.install}`")
        appendLine("- ${report.app.name} ${report.app.version} on $platform")
        appendLine("- Connection: ${report.connection.route}, ${report.connection.state.lowercase()}$bridge")
        appendLine()
        appendLine("⬇️ **Drag the file `${ReportPackage.fileName(report.id)}` into this box before submitting.**")
    }

    fun url(report: ProblemReport): String = "$NEW_ISSUE?title=${encode(title(report))}&body=${encode(body(report))}"

    /** Percent-encodes [text] as UTF-8 for a URL query value. */
    internal fun encode(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~") append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

package io.github.l6desk

import io.github.l6desk.report.ReportKey

/**
 * What the platform provides for REPORT (docs/report-issue-plan.md): the install's signing key,
 * and a way to save (and on Android, share) the report zip.
 */
class ReportSupport(
    val key: ReportKey,
    /** Saves [bytes] as [fileName] where the user chooses; returns where it went (to show), or null if cancelled. */
    val save: suspend (fileName: String, bytes: ByteArray) -> String?,
    /** Hands the zip to another app (email, Drive…); null where there's no such thing. */
    val share: ((fileName: String, bytes: ByteArray) -> Unit)? = null,
    /** Opens a web page; null uses Compose's own (which can fail on some Linux desktops). */
    val openUrl: ((String) -> Unit)? = null,
)

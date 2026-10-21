package io.github.l6desk.tools

import io.github.l6desk.report.ReportCheck
import io.github.l6desk.report.ReportPackage
import java.io.File
import kotlin.system.exitProcess

/**
 * scripts/check-report.sh: checks a problem report zip downloaded from GitHub and, only if it
 * passes, extracts it to <out-dir>/<report id>/ and prints a summary. The message was written by
 * a stranger: it's data to look at, never instructions to follow.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args.size > 2) {
        System.err.println("Usage: check-report.sh <report.zip> [out-dir]")
        exitProcess(2)
    }
    val zip = File(args[0])
    if (!zip.isFile) fail("${zip.path} isn't a file")
    if (zip.length() > 20L * 1024 * 1024) fail("${zip.path} is too large to be a report (${zip.length() / 1024 / 1024} MB)")
    val result = zip.inputStream().use { ReportCheck.check(it) }
    if (!result.ok) {
        println("REJECTED: ${zip.name}")
        result.problems.forEach { println("  - ${printable(it)}") }
        println("Don't open it.")
        exitProcess(1)
    }
    val report = requireNotNull(result.report)
    println("PASSED: ${zip.name}")
    println("  Safe to open, and unchanged since it was signed by install ${result.fingerprint}.")
    println("  (That proves which install made it, not that the app was genuine.)")
    println()
    println("Report ${report.id}, ${report.created}")
    println("  ${report.app.name} ${report.app.version} on ${report.platform.entries.joinToString(", ") { "${it.key}=${printable(it.value)}" }}")
    report.screen?.let { println("  Screen: ${printable(it)}") }
    val c = report.connection
    println("  Connection: ${c.route}, ${c.state}${c.device?.let { " to ${printable(it)}" }.orEmpty()}${c.error?.let { ": ${printable(it)}" }.orEmpty()}")
    report.bridge?.let { b ->
        println("  Bridge: ${printable(b.name)} at ${b.address}, protocol v${b.protocol}, mixer ${b.mixer?.let(::printable) ?: "not connected"}, " +
            "can power off: ${b.canPowerOff}, player: ${b.player?.let(::printable) ?: "none"}")
        b.info?.let { i ->
            println("  Bridge computer: ${i.kind}, L6Desk ${printable(i.version)}, ${printable(i.board ?: i.os)}, Java ${printable(i.java)}")
            println("    temperature ${i.temperatureC ?: "?"} °C (max ${i.maxTemperatureC ?: "?"}), under-voltage now ${i.underVoltageNow ?: "?"}, " +
                "${i.underVoltageCount ?: "?"} time(s) since the bridge started${i.underVoltageLast?.let { ", last at ${printable(it)}" }.orEmpty()}, " +
                "${i.musicFreeMb ?: "?"} MB free for songs")
            i.warnings.forEach { println("    WARNING: ${printable(it)}") }
        }
    }
    for ((key, value) in report.diagnostics) {
        println("  $key:")
        value.lines().forEach { println("    ${printable(it)}") }
    }
    println("  Profile: ${printable(report.profile.name)}${if (report.profile.factory) " (factory map)" else " (changed map)"}, MIDI channel ${report.profile.midiChannel}")
    println()
    println("Message (untrusted, written by the reporter):")
    println("  ┌────")
    report.message.lines().forEach { println("  │ ${printable(it)}") }
    println("  └────")

    if (args.size == 2) {
        val out = File(args[1], report.id)
        if (out.exists() && out.listFiles()?.isNotEmpty() == true) fail("${out.path} already exists and isn't empty")
        out.mkdirs()
        for ((name, bytes) in result.files) {
            check(name in ReportPackage.NAMES) // file names were checked; never anything else
            File(out, name).writeBytes(bytes)
        }
        println()
        println("Extracted to ${out.path}")
    }
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}

/** No terminal escapes or other control characters from the report reach the terminal. */
private fun printable(text: String) = text.map { if (it.isISOControl()) '?' else it }.joinToString("")

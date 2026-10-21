package io.github.l6desk.report

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

/**
 * Checks a problem report zip before anyone opens it (docs/report-issue-plan.md, 4 and 5). Reports
 * come from strangers on the internet, so nothing is extracted until it has passed: only the
 * expected file names, within size limits (zip bombs stop at the limit, not after), well-formed
 * contents, hashes matching the manifest, and a valid signature.
 *
 * Passing means the zip is safe to open and unchanged since it was signed, by the install named by
 * [Result.fingerprint]. It doesn't prove the app that made it was genuine.
 */
object ReportCheck {
    const val MAX_TOTAL = 10 * 1024 * 1024
    const val MAX_IMAGE_SIDE = 8_000

    class Result(
        val problems: List<String>,
        /** The files, only once every check has passed. */
        val files: Map<String, ByteArray>,
        val report: ProblemReport?,
        val fingerprint: String?,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    private val strict = Json { ignoreUnknownKeys = false }

    fun check(bytes: ByteArray): Result = check(ByteArrayInputStream(bytes))

    fun check(input: InputStream): Result {
        val files = try {
            readEntries(input)
        } catch (e: Rejected) {
            return Result(listOf(e.message ?: "rejected"), emptyMap(), null, null)
        }
        val problems = mutableListOf<String>()
        for (name in ReportPackage.NAMES - ReportPackage.SCREENSHOT) if (name !in files) problems += "$name is missing"
        if (problems.isNotEmpty()) return Result(problems, emptyMap(), null, null)

        val manifestBytes = files.getValue(ReportPackage.MANIFEST)
        val manifest = runCatching { strict.decodeFromString(ReportManifest.serializer(), utf8(manifestBytes)) }
            .getOrElse { return Result(listOf("manifest.json isn't a valid manifest: ${it.message?.lineSequence()?.first()}"), emptyMap(), null, null) }

        // The signature first: if it fails, nothing else in the zip can be trusted to mean anything.
        val publicKey = runCatching { Base64.getDecoder().decode(manifest.publicKey) }.getOrNull()
        val signature = runCatching { Base64.getDecoder().decode(utf8(files.getValue(ReportPackage.SIGNATURE)).trim()) }.getOrNull()
        if (manifest.algorithm != ReportPackage.ALGORITHM) problems += "unexpected signature algorithm ${manifest.algorithm}"
        if (publicKey == null || signature == null || !verify(publicKey, manifestBytes, signature)) {
            problems += "the signature doesn't match manifest.json"
            return Result(problems, emptyMap(), null, null)
        }
        val fingerprint = ReportPackage.fingerprint(publicKey)

        val listed = manifest.files.keys
        val present = files.keys - ReportPackage.MANIFEST - ReportPackage.SIGNATURE
        if (listed != present) problems += "manifest.json lists ${listed.sorted()}, the zip holds ${present.sorted()}"
        for (name in listed intersect present) {
            if (sha256(files.getValue(name)).toHex() != manifest.files[name]) problems += "$name has been changed since the report was signed"
        }

        val report = runCatching { strict.decodeFromString(ProblemReport.serializer(), utf8(files.getValue(ReportPackage.REPORT))) }
            .getOrElse {
                problems += "report.json isn't a valid report: ${it.message?.lineSequence()?.first()}"
                null
            }
        if (report != null) {
            if (report.format != ProblemReport.FORMAT) problems += "report format ${report.format} isn't known"
            if (report.id != manifest.id) problems += "report.json's id ${report.id} doesn't match the manifest's ${manifest.id}"
            if (report.install != fingerprint) problems += "report.json names install ${report.install}, but it was signed by $fingerprint"
            if (report.message.length > ProblemReport.MAX_MESSAGE) problems += "the message is longer than ${ProblemReport.MAX_MESSAGE} characters"
        }
        runCatching { checkText(utf8(files.getValue(ReportPackage.EVENTS))) }.onFailure { problems += "events.txt: ${it.message}" }
        files[ReportPackage.SCREENSHOT]?.let { png -> checkPng(png)?.let { problems += "screenshot.png: $it" } }

        return if (problems.isEmpty()) Result(emptyList(), files, report, fingerprint) else Result(problems, emptyMap(), report, fingerprint)
    }

    private class Rejected(message: String) : Exception(message)

    /** Reads the entries into memory, rejecting the zip as soon as anything is out of place. */
    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name !in ReportPackage.NAMES) throw Rejected("unexpected entry \"${printable(name)}\"")
                    if (name in files) throw Rejected("$name appears twice")
                    val limit = ReportPackage.LIMITS.getValue(name)
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (out.size() + n > limit) throw Rejected("$name is larger than ${limit / 1024} KB")
                        if (total > MAX_TOTAL) throw Rejected("the contents are larger than ${MAX_TOTAL / 1024 / 1024} MB")
                        out.write(buffer, 0, n)
                    }
                    files[name] = out.toByteArray()
                }
            }
        } catch (e: ZipException) {
            throw Rejected("not a valid zip: ${e.message}")
        }
        if (files.isEmpty()) throw Rejected("not a zip, or an empty one")
        return files
    }

    private fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance(ReportPackage.ALGORITHM).run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    private fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("not UTF-8 text")
    }

    /** Plain text: no control characters other than new lines and tabs (no terminal escapes). */
    private fun checkText(text: String) {
        val bad = text.firstOrNull { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }
        require(bad == null) { "contains control character U+${bad?.code?.toString(16)?.padStart(4, '0')}" }
    }

    /** Null if [png] is a real PNG of a sensible size; otherwise what's wrong with it. */
    private fun checkPng(png: ByteArray): String? {
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        if (png.size < 33 || !png.copyOfRange(0, 8).contentEquals(signature)) return "not a PNG"
        if (String(png, 12, 4, Charsets.US_ASCII) != "IHDR") return "not a PNG (no header)"
        // Read the size before decoding, so a tiny file claiming a gigantic image is never decoded.
        val width = ByteBuffer.wrap(png, 16, 4).int
        val height = ByteBuffer.wrap(png, 20, 4).int
        if (width !in 1..MAX_IMAGE_SIDE || height !in 1..MAX_IMAGE_SIDE) return "unexpected size ${width}×$height"
        val image = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return "can't be decoded"
        return if (image.width == width && image.height == height) null else "its size doesn't match its header"
    }

    /** A name from the zip, safe to print (no terminal escapes, not too long). */
    private fun printable(text: String): String = text.map { if (it.isISOControl()) '?' else it }.joinToString("").take(80)
}

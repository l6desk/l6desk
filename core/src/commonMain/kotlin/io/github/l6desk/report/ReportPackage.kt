package io.github.l6desk.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * This install's own signing key (ECDSA P-256), made on first use: in the Android Keystore on
 * Android (the private key never leaves it), in the settings folder on desktop. A signature proves
 * a report hasn't been changed since it was made, and links reports made by the same install. It
 * can't prove the app was genuine: nothing without a server can (docs/report-issue-plan.md, 5).
 */
interface ReportKey {
    /** X.509 (SubjectPublicKeyInfo) encoding. */
    val publicKey: ByteArray

    /** SHA256withECDSA signature (DER) over [data]. */
    fun sign(data: ByteArray): ByteArray
}

/** `manifest.json`: the SHA-256 of every other file, and the key that signed it (in `manifest.sig`). */
@Serializable
data class ReportManifest(
    val format: Int,
    val id: String,
    val created: String,
    /** File name → SHA-256 (lower-case hex). */
    val files: Map<String, String>,
    val algorithm: String,
    /** Base64 of the X.509 public key. */
    val publicKey: String,
)

/** Builds a problem report zip. Its layout is fixed; `ReportCheck` rejects anything else. */
@OptIn(ExperimentalEncodingApi::class)
object ReportPackage {
    const val REPORT = "report.json"
    const val EVENTS = "events.txt"
    const val SCREENSHOT = "screenshot.png"
    const val MANIFEST = "manifest.json"
    const val SIGNATURE = "manifest.sig"
    const val ALGORITHM = "SHA256withECDSA"

    /** Every name a report zip may contain; all but the screenshot are required. */
    val NAMES = setOf(REPORT, EVENTS, SCREENSHOT, MANIFEST, SIGNATURE)

    /** Each file's size limit, in bytes; `ReportCheck` rejects a zip with anything larger. */
    val LIMITS = mapOf(
        REPORT to 256 * 1024,
        EVENTS to 256 * 1024,
        SCREENSHOT to 8 * 1024 * 1024,
        MANIFEST to 16 * 1024,
        SIGNATURE to 1024,
    )

    internal val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun fileName(id: String) = "l6desk-report-$id.zip"

    /** report.json's text for [report]. */
    fun reportJson(report: ProblemReport): String = json.encodeToString(ProblemReport.serializer(), report)

    /** A random id, 8 characters from an alphabet without look-alikes. */
    fun newId(random: kotlin.random.Random = kotlin.random.Random.Default): String =
        String(CharArray(8) { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] })

    /** A short, readable fingerprint of a public key: "3f2a-91c0-77de-0b14". */
    fun fingerprint(publicKey: ByteArray): String = sha256(publicKey).toHex().take(16).chunked(4).joinToString("-")

    fun build(report: ProblemReport, events: String, screenshot: ByteArray?, key: ReportKey): ByteArray {
        val files = listOfNotNull(
            REPORT to reportJson(report).encodeToByteArray(),
            EVENTS to events.encodeToByteArray(),
            screenshot?.let { SCREENSHOT to it },
        )
        val manifest = ReportManifest(
            format = report.format,
            id = report.id,
            created = report.created,
            files = files.associate { (name, bytes) -> name to sha256(bytes).toHex() },
            algorithm = ALGORITHM,
            publicKey = Base64.encode(key.publicKey),
        )
        val manifestBytes = json.encodeToString(ReportManifest.serializer(), manifest).encodeToByteArray()
        val signature = Base64.encode(key.sign(manifestBytes)).encodeToByteArray()
        return zip(files + (MANIFEST to manifestBytes) + (SIGNATURE to signature))
    }

    private const val ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** SHA-256 of [data]. */
expect fun sha256(data: ByteArray): ByteArray

/** A zip holding [entries] (name → contents), in order. */
expect fun zip(entries: List<Pair<String, ByteArray>>): ByteArray

/** The operating system, its version, the device or distribution, and the runtime. */
expect fun platformDetails(): Map<String, String>

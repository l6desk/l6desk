package io.github.l6desk.report

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The install's report key in [file] (in the settings folder, readable only by the user), made on
 * first use. An unreadable file is replaced by a new key: reports then start a new install
 * fingerprint, which is all that's lost.
 */
class FileReportKey(private val file: File) : ReportKey {
    private val pair: KeyPair by lazy { runCatching { load() }.getOrNull() ?: create() }

    override val publicKey: ByteArray get() = pair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(ReportPackage.ALGORITHM).run {
            initSign(pair.private)
            update(data)
            sign()
        }

    private fun load(): KeyPair? {
        if (!file.isFile) return null
        val fields = file.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val factory = KeyFactory.getInstance("EC")
        val decoder = Base64.getDecoder()
        val public = factory.generatePublic(X509EncodedKeySpec(decoder.decode(fields.getValue("public"))))
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(decoder.decode(fields.getValue("private"))))
        return KeyPair(public, private)
    }

    private fun create(): KeyPair {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText("")
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
            val encoder = Base64.getEncoder()
            tmp.writeText(
                "# L6Desk's key for signing problem reports (made on this computer; only the public key goes into reports).\n" +
                    "public=${encoder.encodeToString(pair.public.encoded)}\n" +
                    "private=${encoder.encodeToString(pair.private.encoded)}\n",
            )
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        return pair
    }

    companion object {
        const val FILE_NAME = "report-key.txt"
    }
}

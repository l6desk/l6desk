package io.github.l6desk.report

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The install's report key in the Android Keystore: made on first use, and the private key can't be
 * copied off the device. If the Keystore fails (it's been known to on some devices), a key made for
 * this run only is used: the report is still signed, it just won't match this install's others.
 */
class KeystoreReportKey(private val alias: String = "l6desk-report") : ReportKey {
    private val keys: Pair<PrivateKey, PublicKey> by lazy {
        runCatching { fromKeystore() }.getOrElse {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            pair.private to pair.public
        }
    }

    override val publicKey: ByteArray get() = keys.second.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(ReportPackage.ALGORITHM).run {
            initSign(keys.first)
            update(data)
            sign()
        }

    private fun fromKeystore(): Pair<PrivateKey, PublicKey> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return it.privateKey to it.certificate.publicKey }
        val pair: KeyPair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
        }.generateKeyPair()
        return pair.private to pair.public
    }
}

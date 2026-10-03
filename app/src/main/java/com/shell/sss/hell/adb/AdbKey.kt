package com.shell.sss.hell.adb

import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date

/** Clave RSA 2048 usada para autenticarse contra adbd, igual que hace el binario adb. */
object AdbKey {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }.generateKeyPair()

    private val certificate: X509Certificate by lazy { buildCertificate() }

    /** Public key en formato Android ADB (524 bytes little-endian + base64). */
    fun adbPublicKey(): String {
        val key = keyPair.public as RSAPublicKey
        val n = key.modulus
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.modPow(BigInteger.TWO, n)
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.modInverse(two32).negate().mod(two32)

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64) // MODULUS_SIZE_WORDS
        buf.putInt(n0inv.toInt())
        putWords(buf, n)
        putWords(buf, rr)
        buf.putInt(key.publicExponent.toInt())
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP) + " shellsss@android"
    }

    private fun putWords(buf: ByteBuffer, v: BigInteger) {
        val mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)
        var remaining = v
        repeat(64) {
            buf.putInt(remaining.and(mask).toInt())
            remaining = remaining.shiftRight(32)
        }
    }

    fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA1withRSA").apply {
            initSign(keyPair.private)
            update(data)
        }.sign()

    fun certificate(): X509Certificate = certificate

    private fun buildCertificate(): X509Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name("CN=ShellSSS")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 10_000),
            Date(now + 25L * 365 * 24 * 3600 * 1000),
            name,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}

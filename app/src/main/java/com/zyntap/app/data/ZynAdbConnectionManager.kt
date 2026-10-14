package com.zyntap.app.data

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Quản lý kết nối ADB cho libadb-android.
 *
 * Khoá RSA + chứng chỉ được tạo MỘT LẦN rồi lưu trong thư mục riêng của app, để
 * lần sau mở app vẫn dùng đúng khoá đã ghép nối (adbd nhớ khoá này, mất khoá là
 * phải ghép nối lại).
 */
class ZynAdbConnectionManager private constructor(
    private val privateKey: PrivateKey,
    private val certificate: Certificate,
) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "ZynTap"

    companion object {
        private const val KEY_FILE = "adb_key.pk8"
        private const val CERT_FILE = "adb_cert.der"

        @Volatile
        private var instance: ZynAdbConnectionManager? = null

        fun get(context: Context): ZynAdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): ZynAdbConnectionManager {
            val keyFile = File(context.filesDir, KEY_FILE)
            val certFile = File(context.filesDir, CERT_FILE)

            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    val key = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                    return ZynAdbConnectionManager(key, cert)
                }
                // File hỏng -> tạo lại bên dưới
            }

            val pair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()

            val subject = X500Name("CN=ZynTap")
            val notBefore = Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
            val notAfter = Date(System.currentTimeMillis() + 3650L * 24 * 60 * 60 * 1000)
            val serial = BigInteger(64, SecureRandom()).abs()
            val holder = JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            val cert = JcaX509CertificateConverter().getCertificate(holder)

            runCatching {
                keyFile.writeBytes(pair.private.encoded)
                certFile.writeBytes(cert.encoded)
            }
            return ZynAdbConnectionManager(pair.private, cert)
        }
    }
}

package com.dsh.wechatshot.adb

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * 稳定的自签名 ADB 身份。证书和私钥都存放在应用私有目录，重新配对后仍能复用。
 * 注意：不要删除这两个文件，否则设备会把它当成一台新电脑，需要重新配对。
 */
object AdbCrypto {
    private const val TAG = "AdbCrypto"
    private const val KEY_FILE = "adb_private_key.pk8"
    private const val CERT_FILE = "adb_cert.pem"
    private const val KEY_ALGORITHM = "RSA"

    data class Identity(val privateKey: PrivateKey, val certificate: Certificate)

    @Synchronized
    fun loadOrCreate(context: Context): Identity {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)

        val existing = runCatching { readIdentity(keyFile, certFile) }.getOrNull()
        if (existing != null) return existing

        Log.w(TAG, "ADB 身份不存在或已损坏，生成新的 RSA 密钥和自签名证书")
        return generateAndSave(keyFile, certFile)
    }

    private fun readIdentity(keyFile: File, certFile: File): Identity? {
        if (!keyFile.isFile || !certFile.isFile) return null
        val keyBytes = keyFile.readBytes()
        if (keyBytes.isEmpty()) return null
        val key = KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val pem = certFile.readText()
        val base64 = pem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace(Regex("\\s"), "")
        if (base64.isEmpty()) return null
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.decode(base64, Base64.DEFAULT)))
                as X509Certificate
        cert.checkValidity()

        // 用私钥签一段数据，再用证书公钥验签，确认两者匹配。
        val payload = byteArrayOf(0x57, 0x65, 0x43, 0x68, 0x61, 0x74)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(key)
        signer.update(payload)
        val signed = signer.sign()
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(cert.publicKey)
        verifier.update(payload)
        if (!verifier.verify(signed)) {
            Log.w(TAG, "证书与私钥不匹配，重新生成")
            return null
        }
        return Identity(key, cert)
    }

    private fun generateAndSave(keyFile: File, certFile: File): Identity {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(2048, SecureRandom())
        val keyPair: KeyPair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=WeChatShot, O=DSH, C=CN")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        certificate.verify(keyPair.public)

        val keyBytes = keyPair.private.encoded
        val certPem = buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(Base64.encodeToString(certificate.encoded, Base64.NO_WRAP))
            append("\n-----END CERTIFICATE-----\n")
        }
        writeAtomically(keyFile, keyBytes)
        writeAtomically(certFile, certPem.toByteArray(Charsets.US_ASCII))
        Log.i(TAG, "已生成 ADB 身份，设备名=" + deviceName())
        return Identity(keyPair.private, certificate)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty().ifEmpty { "Android" }
        val safe = model.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
        return "wechatshot@" + safe
    }
}

package com.amadeus.whale.pairing

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PinnedTlsTest {
  private fun ensureBc() {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(BouncyCastleProvider())
    }
  }

  private fun makeSelfSignedCa(): Pair<X509Certificate, String> {
    ensureBc()
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(256, SecureRandom())
    val kp: KeyPair = kpg.generateKeyPair()
    val now = System.currentTimeMillis()
    val builder = JcaX509v3CertificateBuilder(
      X500Principal("CN=amadeus-test"),
      BigInteger.ONE,
      Date(now - 1000),
      Date(now + 365L * 24 * 3600 * 1000),
      X500Principal("CN=amadeus-test"),
      kp.public,
    )
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
    val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(kp.private)
    val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
    val fingerprint = sha256(cert.encoded)
    return cert to fingerprint
  }

  @Test fun validatesSelfSignedCaFingerprint() {
    val (cert, fp) = makeSelfSignedCa()
    assertEquals(fp, PinnedTls.sha256Fingerprint(cert))
    val validated = PinnedTls.validateCertificate(cert.encoded, fp)
    assertEquals(cert, validated)
  }

  @Test fun rejectsFingerprintMismatch() {
    val (cert, _) = makeSelfSignedCa()
    assertThrows(SecurityException::class.java) {
      PinnedTls.validateCertificate(cert.encoded, "f".repeat(64))
    }
  }

  @Test fun rejectsNonSelfSigned() {
    ensureBc()
    val kpg = KeyPairGenerator.getInstance("EC"); kpg.initialize(256)
    val kp = kpg.generateKeyPair()
    val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val now = System.currentTimeMillis()
    val builder = JcaX509v3CertificateBuilder(
      X500Principal("CN=issuer"),
      BigInteger.ONE, Date(now - 1000), Date(now + 1000000),
      X500Principal("CN=leaf"), kp.public,
    )
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
    val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(
      builder.build(JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(other.private)))
    // 独立签名者（issuer CN != subject CN 且 self-verify 失败）
    assertThrows(SecurityException::class.java) {
      PinnedTls.validateCertificate(cert.encoded, "f".repeat(64))
    }
  }

  private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it) }
}

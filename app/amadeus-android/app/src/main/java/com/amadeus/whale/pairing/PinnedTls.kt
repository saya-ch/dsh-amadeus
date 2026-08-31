package com.amadeus.whale.pairing

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object PinnedTls {
  fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
      .joinToString("") { "%02x".format(it) }

  fun validateCertificate(caDer: ByteArray, instanceId: String): X509Certificate {
    val factory = CertificateFactory.getInstance("X.509")
    val cert = factory.generateCertificate(caDer.inputStream()) as X509Certificate
    val basic = cert.basicConstraints
    if (basic < 0) throw SecurityException("not a CA certificate")
    if (cert.subjectX500Principal != cert.issuerX500Principal) throw SecurityException("not self-signed")
    try {
      cert.verify(cert.publicKey)
    } catch (error: Exception) {
      throw SecurityException("self-verification failed", error)
    }
    if (sha256Fingerprint(cert) != instanceId) throw SecurityException("CA fingerprint does not match instance id")
    return cert
  }

  fun socketFactory(caDer: ByteArray, instanceId: String): SSLSocketFactory {
    val cert = validateCertificate(caDer, instanceId)
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setCertificateEntry("amadeus", cert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)
    val context = SSLContext.getInstance("TLS")
    context.init(null, tmf.trustManagers, SecureRandom())
    return context.socketFactory
  }

  fun trustManager(caDer: ByteArray, instanceId: String): X509TrustManager {
    val cert = validateCertificate(caDer, instanceId)
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setCertificateEntry("amadeus", cert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
  }
}

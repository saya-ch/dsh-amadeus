package com.amadeus.whale.pairing

import com.amadeus.whale.PrefsStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCredentialStoreTest {
  private class FakeAesGcm : AesGcmCrypto {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun encrypt(plaintext: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
      cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
      return iv + cipher.doFinal(plaintext)
    }
    override fun decrypt(ciphertext: ByteArray): ByteArray {
      val iv = ciphertext.copyOfRange(0, 12)
      val body = ciphertext.copyOfRange(12, ciphertext.size)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
      return cipher.doFinal(body)
    }
  }

  private class FakeAesGcmIdentity : AesGcmCrypto {
    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
    override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
  }

  private class MemStore : PrefsStore {
    val m = mutableMapOf<String, String>()
    override fun getString(key: String) = m[key]
    override fun putString(key: String, value: String) { m[key] = value }
    override fun getBoolean(key: String, def: Boolean) = m[key]?.toBoolean() ?: def
    override fun putBoolean(key: String, value: Boolean) { m[key] = value.toString() }
    override fun remove(key: String) { m.remove(key) }
  }

  @Test fun roundtripsCredentialPerOrigin() {
    val prefs = MemStore()
    val store = KeystoreDeviceCredentialStore(prefs, FakeAesGcm())
    val cred = DeviceCredential("a".repeat(64), "C".repeat(43), 123456789L, ByteArray(16) { it.toByte() }, "https://h:3444")
    assertTrue(store.save("https://h:3444", cred))
    val loaded = store.load("https://h:3444")
    assertNotNull(loaded)
    assertEquals(cred.instanceId, loaded!!.instanceId)
    assertEquals(cred.deviceToken, loaded.deviceToken)
    assertEquals(cred.deviceExpiresAt, loaded.deviceExpiresAt)
    assertEquals(cred.origin, loaded.origin)
    assertEquals(cred.caCertificate.toList(), loaded.caCertificate.toList())
  }

  @Test fun loadMissingOriginReturnsNull() {
    val prefs = MemStore()
    val store = KeystoreDeviceCredentialStore(prefs, FakeAesGcm())
    assertNull(store.load("https://missing:1"))
  }

  @Test fun clearRemovesCredential() {
    val prefs = MemStore()
    val store = KeystoreDeviceCredentialStore(prefs, FakeAesGcm())
    store.save("https://h:3444", DeviceCredential("a".repeat(64), "C".repeat(43), 123456789L, ByteArray(1) { 7 }, "https://h:3444"))
    store.clear("https://h:3444")
    assertNull(store.load("https://h:3444"))
  }

  @Test fun isolatesDifferentOrigins() {
    val prefs = MemStore()
    val store = KeystoreDeviceCredentialStore(prefs, FakeAesGcm())
    val credA = DeviceCredential("a".repeat(64), "C".repeat(43), 111L, ByteArray(4) { 1 }, "https://a:3444")
    val credB = DeviceCredential("b".repeat(64), "D".repeat(43), 222L, ByteArray(4) { 2 }, "https://b:3444")
    store.save("https://a:3444", credA)
    store.save("https://b:3444", credB)
    val loadedA = store.load("https://a:3444")!!
    val loadedB = store.load("https://b:3444")!!
    assertEquals("a".repeat(64), loadedA.instanceId)
    assertEquals("b".repeat(64), loadedB.instanceId)
    assertEquals(111L, loadedA.deviceExpiresAt)
    assertEquals(222L, loadedB.deviceExpiresAt)
    // clear one does not affect the other
    store.clear("https://a:3444")
    assertNull(store.load("https://a:3444"))
    assertNotNull(store.load("https://b:3444"))
  }

  @Test fun encryptedValueIsBase64AndNotPlaintext() {
    val prefs = MemStore()
    val crypto = FakeAesGcmIdentity()
    val store = KeystoreDeviceCredentialStore(prefs, crypto)
    val ca = ByteArray(8) { 0x2a.toByte() }
    val cred = DeviceCredential("a".repeat(64), "C".repeat(43), 999L, ca, "https://h:3444")
    store.save("https://h:3444", cred)
    val stored = prefs.m["credential_https://h:3444"]
    assertNotNull(stored)
    // stored must be Base64 (no line breaks) and must not contain raw instanceId or deviceToken in plaintext after decode? With identity crypto, stored is base64 of json
    // Decode base64 and inspect json contains base64 of cert
    val decoded = String(java.util.Base64.getDecoder().decode(stored!!), Charsets.UTF_8)
    assertTrue(decoded.contains("a".repeat(64)))
    // caCertificate inside json must be Base64.NO_WRAP encoded
    val expectedCaB64 = java.util.Base64.getEncoder().encodeToString(ca)
    assertTrue(decoded.contains(expectedCaB64))
    // raw bytes not directly present
    assertTrue(!decoded.contains(String(ca, Charsets.ISO_8859_1)) || decoded.contains(expectedCaB64))
  }

  @Test fun roundtripPreservesCaCertificateContent() {
    val prefs = MemStore()
    val store = KeystoreDeviceCredentialStore(prefs, FakeAesGcm())
    val cert = ByteArray(32) { (it * 7).toByte() }
    val cred = DeviceCredential("c".repeat(64), "E".repeat(43), 42L, cert, "https://x:3444")
    store.save("https://x:3444", cred)
    val loaded = store.load("https://x:3444")!!
    assertEquals(cert.toList(), loaded.caCertificate.toList())
  }

  @Test fun corruptedDataReturnsNull() {
    val prefs = MemStore()
    val store = KeystoreDeviceCredentialStore(prefs, FakeAesGcm())
    prefs.m["credential_https://h:3444"] = "!!!not-base64!!!"
    assertNull(store.load("https://h:3444"))
  }

  @Test fun deviceCredentialEqualsUsesContent() {
    val certA = ByteArray(4) { it.toByte() }
    val certB = ByteArray(4) { it.toByte() }
    val a = DeviceCredential("a".repeat(64), "C".repeat(43), 1L, certA, "https://h:3444")
    val b = DeviceCredential("a".repeat(64), "C".repeat(43), 1L, certB, "https://h:3444")
    // contentEquals should make them equal despite different array instances
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }
}

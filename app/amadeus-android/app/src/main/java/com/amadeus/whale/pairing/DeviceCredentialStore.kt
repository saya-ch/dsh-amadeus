package com.amadeus.whale.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.amadeus.whale.data.store.PrefsStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class DeviceCredential(
  val instanceId: String,
  val deviceToken: String,
  val deviceExpiresAt: Long,
  val caCertificate: ByteArray,
  val origin: String,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is DeviceCredential) return false
    return instanceId == other.instanceId &&
      deviceToken == other.deviceToken &&
      deviceExpiresAt == other.deviceExpiresAt &&
      caCertificate.contentEquals(other.caCertificate) &&
      origin == other.origin
  }

  override fun hashCode(): Int {
    var result = instanceId.hashCode()
    result = 31 * result + deviceToken.hashCode()
    result = 31 * result + deviceExpiresAt.hashCode()
    result = 31 * result + caCertificate.contentHashCode()
    result = 31 * result + origin.hashCode()
    return result
  }
}

interface AesGcmCrypto {
  fun encrypt(plaintext: ByteArray): ByteArray
  fun decrypt(ciphertext: ByteArray): ByteArray
}

/** Android Keystore AES/GCM; key never leaves the secure element. Alias amw_device_v1 per spec. */
class KeystoreAesGcmCrypto(private val alias: String) : AesGcmCrypto {
  override fun encrypt(plaintext: ByteArray): ByteArray = crypt(plaintext, Cipher.ENCRYPT_MODE)
  override fun decrypt(ciphertext: ByteArray): ByteArray = crypt(ciphertext, Cipher.DECRYPT_MODE)

  private fun crypt(input: ByteArray, mode: Int): ByteArray {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!ks.containsAlias(alias)) {
      val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      generator.init(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setRandomizedEncryptionRequired(true)
          .build(),
      )
      generator.generateKey()
    }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    return if (mode == Cipher.ENCRYPT_MODE) {
      cipher.init(Cipher.ENCRYPT_MODE, ks.getKey(alias, null))
      val iv = cipher.iv
      iv + cipher.doFinal(input)
    } else {
      val iv = input.copyOfRange(0, 12)
      val body = input.copyOfRange(12, input.size)
      cipher.init(Cipher.DECRYPT_MODE, ks.getKey(alias, null), GCMParameterSpec(128, iv))
      cipher.doFinal(body)
    }
  }
}

interface DeviceCredentialStore {
  fun save(origin: String, credential: DeviceCredential): Boolean
  fun load(origin: String): DeviceCredential?
  fun clear(origin: String)
}

class KeystoreDeviceCredentialStore(
  private val prefs: PrefsStore,
  private val crypto: AesGcmCrypto = KeystoreAesGcmCrypto("amw_device_v1"),
) : DeviceCredentialStore {
  private fun key(origin: String) = "credential_$origin"

  override fun save(origin: String, credential: DeviceCredential): Boolean {
    val record = buildJsonObject {
      put("instanceId", credential.instanceId)
      put("deviceToken", credential.deviceToken)
      put("deviceExpiresAt", credential.deviceExpiresAt)
      put("caCertificate", Base64.getEncoder().encodeToString(credential.caCertificate))
      put("origin", credential.origin)
    }.toString()
    val encrypted = Base64.getEncoder().encodeToString(crypto.encrypt(record.toByteArray(Charsets.UTF_8)))
    prefs.putString(key(origin), encrypted)
    return true
  }

  override fun load(origin: String): DeviceCredential? {
    val encrypted = prefs.getString(key(origin)) ?: return null
    return try {
      val decoded = Base64.getDecoder().decode(encrypted)
      val decrypted = crypto.decrypt(decoded)
      val jsonStr = String(decrypted, Charsets.UTF_8)
      val obj = Json.parseToJsonElement(jsonStr).jsonObject
      DeviceCredential(
        instanceId = obj["instanceId"]!!.jsonPrimitive.content,
        deviceToken = obj["deviceToken"]!!.jsonPrimitive.content,
        deviceExpiresAt = obj["deviceExpiresAt"]!!.jsonPrimitive.long,
        caCertificate = Base64.getDecoder().decode(obj["caCertificate"]!!.jsonPrimitive.content),
        origin = obj["origin"]!!.jsonPrimitive.content,
      )
    } catch (_: Exception) {
      null
    }
  }

  override fun clear(origin: String) {
    (prefs as com.amadeus.whale.data.store.PrefsStore).remove(key(origin))
  }
}

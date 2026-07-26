package com.mineg.mobile.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mineg.mobile.contracts.SecureStorePort
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureStorePort(context: Context) : SecureStorePort {
  private val preferences = context.getSharedPreferences("mineg_secure_values", Context.MODE_PRIVATE)
  private val keyAlias = "mineg.foundation.wrap.v1"

  override fun readSecret(name: String): ByteArray? {
    val encoded = preferences.getString(name, null) ?: return null
    return runCatching {
      val packet = Base64.decode(encoded, Base64.NO_WRAP)
      require(packet.size >= 4)
      val buffer = ByteBuffer.wrap(packet)
      val ivSize = buffer.int
      require(ivSize in 12..32 && buffer.remaining() > ivSize)
      val iv = ByteArray(ivSize)
      buffer.get(iv)
      val ciphertext = ByteArray(buffer.remaining())
      buffer.get(ciphertext)
      Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        doFinal(ciphertext)
      }
    }.getOrNull()
  }

  override fun writeSecret(name: String, value: ByteArray) {
    require(name.isNotBlank() && value.isNotEmpty())
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val ciphertext = cipher.doFinal(value)
    val packet = ByteBuffer.allocate(4 + cipher.iv.size + ciphertext.size)
      .putInt(cipher.iv.size)
      .put(cipher.iv)
      .put(ciphertext)
      .array()
    preferences.edit().putString(name, Base64.encodeToString(packet, Base64.NO_WRAP)).apply()
    packet.fill(0)
    ciphertext.fill(0)
  }

  override fun deleteSecret(name: String) {
    preferences.edit().remove(name).apply()
  }

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(
        KeyGenParameterSpec.Builder(
          keyAlias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build(),
      )
      generateKey()
    }
  }
}

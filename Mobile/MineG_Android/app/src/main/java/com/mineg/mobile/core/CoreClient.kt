package com.mineg.mobile.core

fun interface CoreEventListener {
  fun onEvent(eventJson: String)
}

data class UserKeyBundleMaterial(
  val publicKey: ByteArray,
  val encryptedKeyBundle: ByteArray,
  val kdfParametersJson: String,
)

class CoreClient : AutoCloseable {
  private var handle: Long = 0

  @Synchronized
  fun initialize(databasePath: String) {
    check(handle == 0L) { "CoreClient is already initialized" }
    require(databasePath.isNotBlank())
    handle = NativeBridge.nativeCreate(databasePath)
  }

  @Synchronized
  fun execute(operationId: Long, commandJson: String): String {
    require(operationId > 0)
    return NativeBridge.nativeExecute(requireHandle(), operationId, commandJson)
  }

  @Synchronized
  fun query(queryJson: String): String = NativeBridge.nativeQuery(requireHandle(), queryJson)

  @Synchronized
  fun subscribe(listener: CoreEventListener): Long = NativeBridge.nativeSubscribe(requireHandle(), listener)

  @Synchronized
  fun unsubscribe(subscriptionToken: Long) {
    NativeBridge.nativeUnsubscribe(requireHandle(), subscriptionToken)
  }

  @Synchronized
  fun cancel(operationId: Long) {
    NativeBridge.nativeCancel(requireHandle(), operationId)
  }

  @Synchronized
  fun randomKey(): ByteArray = NativeBridge.nativeRandomKey()

  @Synchronized
  fun createUserKeyBundle(password: ByteArray): UserKeyBundleMaterial {
    require(password.size in 8..256)
    val values = NativeBridge.nativeCreateUserKeyBundle(password)
    check(values.size == 3 && values[0].size == 32 && values[1].isNotEmpty())
    return UserKeyBundleMaterial(values[0], values[1], values[2].toString(Charsets.UTF_8))
  }

  @Synchronized
  fun unlockUserKeyBundle(
    password: ByteArray,
    publicKey: ByteArray,
    encryptedBundle: ByteArray,
    deviceWrapKey: ByteArray,
  ): ByteArray {
    require(password.size in 8..256 && publicKey.size == 32 && encryptedBundle.isNotEmpty() && deviceWrapKey.size == 32)
    return NativeBridge.nativeUnlockUserKeyBundle(requireHandle(), password, publicKey, encryptedBundle, deviceWrapKey)
  }

  @Synchronized
  fun restoreUserKeyBundle(publicKey: ByteArray, deviceWrapKey: ByteArray, unlockBlob: ByteArray) {
    require(publicKey.size == 32 && deviceWrapKey.size == 32 && unlockBlob.isNotEmpty())
    NativeBridge.nativeRestoreUserKeyBundle(requireHandle(), publicKey, deviceWrapKey, unlockBlob)
  }

  @Synchronized
  fun unlockFamilyKeyEnvelope(encryptedEnvelope: ByteArray) {
    require(encryptedEnvelope.size == 80)
    NativeBridge.nativeUnlockFamilyKeyEnvelope(requireHandle(), encryptedEnvelope)
  }

  @Synchronized
  fun createFamilyKeyEnvelope(recipientPublicKey: ByteArray, bootstrapIfNeeded: Boolean): ByteArray {
    require(recipientPublicKey.size == 32)
    return NativeBridge.nativeCreateFamilyKeyEnvelope(requireHandle(), recipientPublicKey, bootstrapIfNeeded)
      .also { check(it.size == 80) }
  }

  @Synchronized
  fun lockKeys() {
    NativeBridge.nativeLockKeys(requireHandle())
  }

  @Synchronized
  fun createMediaKeyEnvelope(mediaId: String): ByteArray {
    require(mediaId.isNotBlank())
    return NativeBridge.nativeCreateMediaKeyEnvelope(requireHandle(), mediaId)
      .also { check(it.size == 80) }
  }

  @Synchronized
  fun computeDedupeFingerprint(descriptor: Int, mediaType: String): ByteArray {
    require(descriptor >= 0 && mediaType.isNotBlank())
    return NativeBridge.nativeComputeDedupeFingerprint(requireHandle(), descriptor, mediaType)
      .also { check(it.size == 32) }
  }

  @Synchronized
  fun encryptMediaResource(
    descriptor: Int,
    ciphertextPath: String,
    mediaId: String,
    resourceId: String,
    resourceType: String,
    encryptedMediaKey: ByteArray,
  ): String {
    require(descriptor >= 0 && ciphertextPath.isNotBlank() && mediaId.isNotBlank() && resourceId.isNotBlank())
    require(resourceType.isNotBlank() && encryptedMediaKey.size == 80)
    return NativeBridge.nativeEncryptMediaResource(
      requireHandle(), descriptor, ciphertextPath, mediaId, resourceId, resourceType, encryptedMediaKey,
    )
  }

  @Synchronized
  fun encryptMediaManifest(mediaId: String, manifestJson: ByteArray, encryptedMediaKey: ByteArray): ByteArray {
    require(mediaId.isNotBlank() && manifestJson.isNotEmpty() && encryptedMediaKey.size == 80)
    return NativeBridge.nativeEncryptMediaManifest(requireHandle(), mediaId, manifestJson, encryptedMediaKey)
  }

  @Synchronized
  fun encryptResource(descriptor: Int, ciphertextPath: String, key: ByteArray) {
    require(key.size == 32)
    NativeBridge.nativeEncryptFd(requireHandle(), descriptor, ciphertextPath, key)
  }

  @Synchronized
  override fun close() {
    if (handle != 0L) {
      NativeBridge.nativeClose(handle)
      handle = 0
    }
  }

  private fun requireHandle(): Long = handle.also { check(it != 0L) { "CoreClient is closed" } }
}

package com.mineg.mobile.core

internal object NativeBridge {
  init {
    System.loadLibrary("sodium")
    System.loadLibrary("mineg_core")
  }

  external fun nativeCreate(databasePath: String): Long
  external fun nativeExecute(handle: Long, operationId: Long, commandJson: String): String
  external fun nativeQuery(handle: Long, queryJson: String): String
  external fun nativeSubscribe(handle: Long, listener: CoreEventListener): Long
  external fun nativeUnsubscribe(handle: Long, subscriptionToken: Long)
  external fun nativeCancel(handle: Long, operationId: Long)
  external fun nativeRandomKey(): ByteArray
  external fun nativeCreateUserKeyBundle(password: ByteArray): Array<ByteArray>
  external fun nativeUnlockUserKeyBundle(
    handle: Long,
    password: ByteArray,
    publicKey: ByteArray,
    encryptedBundle: ByteArray,
    deviceWrapKey: ByteArray,
  ): ByteArray
  external fun nativeRestoreUserKeyBundle(handle: Long, publicKey: ByteArray, deviceWrapKey: ByteArray, unlockBlob: ByteArray)
  external fun nativeUnlockFamilyKeyEnvelope(handle: Long, encryptedEnvelope: ByteArray)
  external fun nativeCreateFamilyKeyEnvelope(handle: Long, recipientPublicKey: ByteArray, bootstrapIfNeeded: Boolean): ByteArray
  external fun nativeLockKeys(handle: Long)
  external fun nativeCreateMediaKeyEnvelope(handle: Long, mediaId: String): ByteArray
  external fun nativeComputeDedupeFingerprint(handle: Long, descriptor: Int, mediaType: String): ByteArray
  external fun nativeEncryptMediaResource(
    handle: Long,
    descriptor: Int,
    ciphertextPath: String,
    mediaId: String,
    resourceId: String,
    resourceType: String,
    encryptedMediaKey: ByteArray,
  ): String
  external fun nativeEncryptMediaManifest(
    handle: Long,
    mediaId: String,
    manifestJson: ByteArray,
    encryptedMediaKey: ByteArray,
  ): ByteArray
  external fun nativeEncryptFd(handle: Long, descriptor: Int, ciphertextPath: String, key: ByteArray)
  external fun nativeClose(handle: Long)
}

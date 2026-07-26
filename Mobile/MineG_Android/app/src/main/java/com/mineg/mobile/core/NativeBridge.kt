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
  external fun nativeEncryptFd(handle: Long, descriptor: Int, ciphertextPath: String, key: ByteArray)
  external fun nativeClose(handle: Long)
}

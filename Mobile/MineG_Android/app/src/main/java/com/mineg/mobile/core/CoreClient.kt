package com.mineg.mobile.core

fun interface CoreEventListener {
  fun onEvent(eventJson: String)
}

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

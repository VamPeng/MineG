/**
 * Thread-safe Kotlin ownership wrapper around the native MineG Core handle.
 *
 * The client performs only lifecycle and envelope validation; business interpretation belongs to
 * domain gateways and operation sequencing belongs to [CoreOperationRunner].
 */
package com.mineg.mobile.core

import com.mineg.mobile.core.protocol.CoreOperationStep

fun interface CoreEventListener {
  /** Receives one unmodified event envelope emitted by Core. */
  fun onEvent(eventJson: String)
}

/** Owns exactly one native Core instance and serializes every JNI call on that handle. */
class CoreClient : AutoCloseable {
  private var handle: Long = 0

  /** Creates the native instance against [databasePath]; may be called only once before closing. */
  @Synchronized
  fun initialize(databasePath: String) {
    check(handle == 0L) { "CoreClient is already initialized" }
    require(databasePath.isNotBlank())
    handle = NativeBridge.nativeCreate(databasePath)
  }

  /** Executes a synchronous Core command that cannot request platform effects. */
  @Synchronized
  fun execute(operationId: Long, commandJson: String): String {
    require(operationId > 0)
    return NativeBridge.nativeExecute(requireHandle(), operationId, commandJson)
  }

  /** Starts an effect-capable command and returns its first protocol step. */
  @Synchronized
  fun startOperation(operationId: Long, commandJson: String): CoreOperationStep {
    require(operationId > 0 && commandJson.isNotBlank())
    return CoreOperationStep.parse(
      NativeBridge.nativeStartOperation(requireHandle(), operationId, commandJson),
    )
  }

  /** Resumes an operation with the exact effect result requested by its prior step. */
  @Synchronized
  fun resumeOperation(operationId: Long, effectResultJson: String): CoreOperationStep {
    require(operationId > 0 && effectResultJson.isNotBlank())
    return CoreOperationStep.parse(
      NativeBridge.nativeResumeOperation(requireHandle(), operationId, effectResultJson),
    )
  }

  /** Returns all durable in-flight operations that Core can recover after process restart. */
  @Synchronized
  fun recoverOperations(): List<CoreOperationStep> =
    CoreOperationStep.parseRecovery(NativeBridge.nativeRecoverOperations(requireHandle()))

  /** Executes a side-effect-free snapshot query. */
  @Synchronized
  fun query(queryJson: String): String = NativeBridge.nativeQuery(requireHandle(), queryJson)

  /** Registers an event listener and returns Core's subscription token. */
  @Synchronized
  fun subscribe(listener: CoreEventListener): Long = NativeBridge.nativeSubscribe(requireHandle(), listener)

  /** Removes the listener associated with [subscriptionToken]. */
  @Synchronized
  fun unsubscribe(subscriptionToken: Long) {
    NativeBridge.nativeUnsubscribe(requireHandle(), subscriptionToken)
  }

  /** Requests cancellation of one active operation. */
  @Synchronized
  fun cancel(operationId: Long) {
    NativeBridge.nativeCancel(requireHandle(), operationId)
  }

  /** Releases the native instance; repeated calls are harmless. */
  @Synchronized
  override fun close() {
    if (handle != 0L) {
      NativeBridge.nativeClose(handle)
      handle = 0
    }
  }

  /** Returns the live handle and rejects every operation after [close]. */
  private fun requireHandle(): Long = handle.also { check(it != 0L) { "CoreClient is closed" } }
}

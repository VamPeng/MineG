/** Raw JNI declarations; all lifecycle and envelope validation belongs to [CoreClient]. */
package com.mineg.mobile.core

/** Internal native symbol table for the MineG C++ Core library. */
internal object NativeBridge {
  init {
    System.loadLibrary("sodium")
    System.loadLibrary("mineg_core")
  }

  /** Creates a Core instance and returns its opaque native handle. */
  external fun nativeCreate(databasePath: String): Long
  /** Executes a synchronous command. */
  external fun nativeExecute(handle: Long, operationId: Long, commandJson: String): String
  /** Starts an effect-capable command. */
  external fun nativeStartOperation(handle: Long, operationId: Long, commandJson: String): String
  /** Resumes a command with one platform effect result. */
  external fun nativeResumeOperation(handle: Long, operationId: Long, effectResultJson: String): String
  /** Recovers durable in-flight operations. */
  external fun nativeRecoverOperations(handle: Long): String
  /** Executes a side-effect-free snapshot query. */
  external fun nativeQuery(handle: Long, queryJson: String): String
  /** Subscribes a Kotlin listener to Core events. */
  external fun nativeSubscribe(handle: Long, listener: CoreEventListener): Long
  /** Removes one Core event subscription. */
  external fun nativeUnsubscribe(handle: Long, subscriptionToken: Long)
  /** Requests cancellation of one operation. */
  external fun nativeCancel(handle: Long, operationId: Long)
  /** Releases the native Core instance. */
  external fun nativeClose(handle: Long)
}

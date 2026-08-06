/** Core operation/effect handshake independent of every business contract. */
package com.mineg.mobile.core

import com.mineg.mobile.core.effect.PlatformEffectDispatcher
import com.mineg.mobile.core.protocol.CoreOperationStatus
import com.mineg.mobile.core.protocol.CoreOperationStep

/**
 * Drives a Core operation by executing each requested Android platform effect in sequence.
 *
 * The runner never interprets domain payloads. It only maintains the operation/effect handshake
 * until Core reports a terminal status.
 */
class CoreOperationRunner(
  private val core: CoreClient,
  private val dispatcher: PlatformEffectDispatcher,
) {
  /** Resumes [initial] until it completes, fails, is cancelled, or exceeds the safety limit. */
  suspend fun run(initial: CoreOperationStep): CoreOperationStep {
    var step = initial
    var dispatchedEffects = 0
    while (step.status == CoreOperationStatus.WAITING_FOR_EFFECT) {
      // Bound malformed or cyclic Core workflows so Android cannot loop indefinitely.
      check(++dispatchedEffects <= MAX_EFFECTS_PER_RUN) { "Core operation exceeded effect limit" }
      val result = dispatcher.dispatch(checkNotNull(step.effect))
      step = core.resumeOperation(step.operationId, result.toJson())
    }
    return step
  }

  /** Recovers all persisted operations and drives each one through the same effect pipeline. */
  suspend fun recover(): List<CoreOperationStep> = core.recoverOperations().map { run(it) }

  private companion object {
    const val MAX_EFFECTS_PER_RUN = 10_000
  }
}

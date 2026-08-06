/** Internal helper shared by domain gateways that use the same Core operation envelope. */
package com.mineg.mobile.bridge.internal

import com.mineg.mobile.core.protocol.CoreProblem
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import com.mineg.mobile.core.protocol.CoreOperationStatus
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Executes one JSON Core contract while keeping operation identifiers and terminal error mapping
 * consistent across domain-specific gateways.
 */
internal class CoreContractOperationExecutor(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
  initialOperationId: Long,
  private val defaultErrorCode: String,
  private val defaultMessageKey: String,
  private val pendingOperationMessage: String,
) {
  private val operationIds = AtomicLong(initialOperationId)

  /** Drives all requested platform effects and returns the completed JSON result. */
  suspend fun execute(command: JSONObject): JSONObject {
    val terminal = runner.run(core.startOperation(operationIds.getAndIncrement(), command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> JSONObject(terminal.resultJson ?: "{}")
      CoreOperationStatus.FAILED -> {
        val error = terminal.errorJson?.let(::JSONObject) ?: JSONObject()
        throw CoreProblem(
          error.optString("code", defaultErrorCode),
          error.optString("messageKey", defaultMessageKey),
          error.optBoolean("retryable", false),
          error.optString("requestId"),
        )
      }
      CoreOperationStatus.CANCELLED -> throw CoreProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error(pendingOperationMessage)
    }
  }
}

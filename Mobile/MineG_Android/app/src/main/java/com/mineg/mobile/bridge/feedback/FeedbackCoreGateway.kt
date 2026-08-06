/** User-feedback submission boundary for the shared Stage 06 wire contract. */
package com.mineg.mobile.bridge.feedback

import com.mineg.mobile.bridge.internal.CoreContractOperationExecutor
import com.mineg.mobile.bridge.shared.model.FeedbackSubmissionResult
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.UUID
import org.json.JSONObject

/** Submits feedback through Core without letting Android interpret server acceptance. */
class FeedbackCoreGateway(core: CoreClient, runner: CoreOperationRunner) {
  private val executor = CoreContractOperationExecutor(
    core,
    runner,
    initialOperationId = 6_200_000_000L,
    defaultErrorCode = "STAGE06_UNAVAILABLE",
    defaultMessageKey = "stage06.unavailable",
    pendingOperationMessage = "CoreOperationRunner returned a pending feedback operation",
  )

  /** Sends one idempotent feedback submission and returns its server-issued identifier. */
  suspend fun sendFeedback(
    category: String,
    description: String,
    contact: String,
    appVersion: String,
    osVersion: String,
  ): FeedbackSubmissionResult = executor.execute(
    JSONObject()
      .put("contractVersion", CONTRACT_VERSION)
      .put("type", "SubmitFeedback")
      .put("category", category)
      .put("description", description)
      .put("contact", contact)
      .put("appVersion", appVersion)
      .put("osVersion", osVersion)
      .put("idempotencyKey", UUID.randomUUID().toString()),
  ).run {
    FeedbackSubmissionResult(
      feedbackId = getString("feedbackId"),
      outcome = getString("outcome"),
      createdAt = getString("createdAt"),
    )
  }

  private companion object {
    const val CONTRACT_VERSION = "stage06-v1"
  }
}

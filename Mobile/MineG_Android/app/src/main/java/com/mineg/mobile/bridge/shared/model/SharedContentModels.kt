/** Immutable shared-space, recycle-bin and feedback result models. */
package com.mineg.mobile.bridge.shared.model

import com.mineg.mobile.bridge.media.model.PrivateMediaResourceSummary

data class PrivateMediaShareResult(
  val mediaId: String,
  val state: String,
  val outcome: String,
  val effectiveAt: String,
)

data class SharedMediaOwner(
  val id: String,
  val nickname: String,
)

data class SharedMediaSummary(
  val id: String,
  val owner: SharedMediaOwner,
  val mediaType: String,
  val capturedAt: String,
  val createdAt: String,
  val durationMs: Long?,
  val originalTotalSize: Long,
)

data class SharedMediaPage(
  val items: List<SharedMediaSummary>,
  val nextCursor: String?,
  val fullyLoaded: Boolean,
)

data class SharedMediaDetail(
  val id: String,
  val owner: SharedMediaOwner,
  val mediaType: String,
  val capturedAt: String,
  val createdAt: String,
  val width: Int?,
  val height: Int?,
  val durationMs: Long?,
  val originalTotalSize: Long,
  val resources: List<PrivateMediaResourceSummary>,
)

data class TrashMediaSummary(
  val id: String,
  val mediaType: String,
  val capturedAt: String,
  val createdAt: String,
  val durationMs: Long?,
  val originalTotalSize: Long,
  val trashedAt: String,
)

data class TrashMediaPage(
  val items: List<TrashMediaSummary>,
  val nextCursor: String?,
  val fullyLoaded: Boolean,
)

data class TrashMediaRestoreResult(
  val mediaId: String,
  val outcome: String,
  val restoredAt: String,
)

data class FeedbackSubmissionResult(
  val feedbackId: String,
  val outcome: String,
  val createdAt: String,
)

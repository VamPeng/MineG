package com.mineg.mobile.contracts

data class PrivateMediaShareResult(
  val mediaId: String,
  val state: String,
  val outcome: String,
  val effectiveAt: String,
)

data class FamilyMediaOwner(
  val id: String,
  val nickname: String,
)

data class FamilyMediaSummary(
  val id: String,
  val owner: FamilyMediaOwner,
  val mediaType: String,
  val capturedAt: String,
  val createdAt: String,
  val durationMs: Long?,
  val originalTotalSize: Long,
)

data class FamilyMediaPage(
  val items: List<FamilyMediaSummary>,
  val nextCursor: String?,
  val fullyLoaded: Boolean,
)

data class FamilyMediaDetail(
  val id: String,
  val owner: FamilyMediaOwner,
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

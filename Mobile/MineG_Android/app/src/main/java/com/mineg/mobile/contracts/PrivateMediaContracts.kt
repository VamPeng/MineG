package com.mineg.mobile.contracts

data class OwnerMediaSummary(
  val id: String,
  val mediaType: String,
  val contentRevision: Int,
  val capturedAt: String,
  val createdAt: String,
)

interface OwnerMediaClient {
  suspend fun listOwnerMedia(limit: Int = 100): List<OwnerMediaSummary>
}

data class PrivateMediaResourceSummary(
  val resourceId: String,
  val resourceType: String,
  val mimeType: String,
  val contentSize: Long,
  val contentSha256: String,
)

data class PrivateMediaSummary(
  val id: String,
  val mediaType: String,
  val capturedAt: String,
  val createdAt: String,
  val durationMs: Long?,
  val originalTotalSize: Long,
  val previewResource: PrivateMediaResourceSummary?,
  val localPlatformAssetRef: String? = null,
  val localSourceUri: String? = null,
)

data class PrivateMediaPage(
  val items: List<PrivateMediaSummary>,
  val nextCursor: String?,
  val fullyLoaded: Boolean,
  val refreshedAt: String?,
)

data class PrivateMediaDetail(
  val id: String,
  val mediaType: String,
  val capturedAt: String,
  val createdAt: String,
  val width: Int?,
  val height: Int?,
  val durationMs: Long?,
  val originalTotalSize: Long,
  val resources: List<PrivateMediaResourceSummary>,
  val localPlatformAssetRef: String? = null,
  val localSourceUri: String? = null,
)

/** An in-memory-only source for a temporary file that Core has already integrity-checked. */
data class PrivateMediaView(
  val mediaId: String,
  val resourceType: String,
  val mimeType: String,
  val viewHandle: String,
  val sourceUri: String,
)

data class PrivateMediaTrashResult(
  val mediaId: String,
  val outcome: String,
  val trashedAt: String,
)

data class PrivateMediaSaveResult(
  val mediaId: String,
  val state: String,
  val savedResourceCount: Int,
)

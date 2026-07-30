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

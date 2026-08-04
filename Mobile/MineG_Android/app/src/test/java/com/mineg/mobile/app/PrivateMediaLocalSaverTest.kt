package com.mineg.mobile.app

import com.mineg.mobile.contracts.PrivateMediaDetail
import com.mineg.mobile.contracts.PrivateMediaResourceSummary
import com.mineg.mobile.contracts.PrivateMediaSaveResult
import com.mineg.mobile.contracts.SystemAlbumWriteRequest
import com.mineg.mobile.contracts.SystemAlbumWriteResult
import com.mineg.mobile.contracts.SystemAlbumWriterPort
import com.mineg.mobile.platform.PrivateOriginalDiskStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PrivateMediaLocalSaverTest {
  @Test
  fun validMappingDeletesCacheWithoutCopyOrReceiptWrite() = runTest {
    withCache { cache, _ ->
      val album = FakeAlbum(mappingPresent = true)
      val receipts = FakeReceipts()

      assertEquals("COMPLETED", saver(cache, album, receipts).save(USER, detail(LOCAL_MAPPING)).state)
      assertEquals(0, album.writeCalls)
      assertEquals(0, receipts.calls)
      assertEquals(null, cache.get(USER, MEDIA_ID, CONTENT.size.toLong(), SHA256))
    }
  }

  @Test
  fun missingCacheReturnsOriginalNotReadyWithoutAlbumOrReceiptCall() = runTest {
    withEmptyCache { cache, _ ->
      val album = FakeAlbum()
      val receipts = FakeReceipts()

      assertEquals("PRIVATE_MEDIA_ORIGINAL_NOT_READY", saver(cache, album, receipts).save(USER, detail()).state)
      assertEquals(0, album.writeCalls)
      assertEquals(0, receipts.calls)
    }
  }

  @Test
  fun validCacheWritesAlbumThenReceiptThenDeletesCache() = runTest {
    withCache { cache, _ ->
      val events = mutableListOf<String>()
      val album = FakeAlbum(events = events)
      val receipts = FakeReceipts(events = events)

      assertEquals("COMPLETED", saver(cache, album, receipts).save(USER, detail()).state)
      assertEquals(1, album.writeCalls)
      assertEquals(1, receipts.calls)
      assertEquals(null, cache.get(USER, MEDIA_ID, CONTENT.size.toLong(), SHA256))
      assertEquals("android:media-store:77", receipts.platformAssetRef)
      assertEquals(listOf("write", "receipt"), album.events)
    }
  }

  @Test
  fun receiptFailureDeletesNewAlbumEntryAndRetainsCache() = runTest {
    withCache { cache, root ->
      val album = FakeAlbum()
      val receipts = FakeReceipts(failure = IOException())

      assertFailsWith<IOException> { saver(cache, album, receipts).save(USER, detail()) }
      assertEquals(listOf("android:media-store:77"), album.deletedRefs)
      assertEquals(true, cache.get(USER, MEDIA_ID, CONTENT.size.toLong(), SHA256) != null)
      assertEquals(true, root.isDirectory)
    }
  }

  @Test
  fun cleanupRetryUsesMappingAndNeverCopiesAgain() = runTest {
    withCache { cache, root ->
      val album = FakeAlbum(mappingPresent = true)
      val temporaryDirectory = File(requireNotNull(cache.fileForTesting(USER, MEDIA_ID)).parentFile, "$MEDIA_ID.tmp")
      check(temporaryDirectory.mkdirs())
      check(File(temporaryDirectory, "blocked").createNewFile())

      assertEquals("PRIVATE_MEDIA_CACHE_CLEANUP_FAILED", saver(cache, album, FakeReceipts()).save(USER, detail(LOCAL_MAPPING)).state)
      temporaryDirectory.deleteRecursively()
      assertEquals("COMPLETED", saver(cache, album, FakeReceipts()).save(USER, detail(LOCAL_MAPPING)).state)
      assertEquals(0, album.writeCalls)
    }
  }

  @Test
  fun newWriteReceiptThenCleanupFailureRetriesWithoutAnotherCopy() = runTest {
    withCache { cache, root ->
      val album = FakeAlbum()
      val temporaryDirectory = File(requireNotNull(cache.fileForTesting(USER, MEDIA_ID)).parentFile, "$MEDIA_ID.tmp")
      check(temporaryDirectory.mkdirs())
      check(File(temporaryDirectory, "blocked").createNewFile())
      val localSaver = saver(cache, album, FakeReceipts())

      assertEquals("PRIVATE_MEDIA_CACHE_CLEANUP_FAILED", localSaver.save(USER, detail()).state)
      temporaryDirectory.deleteRecursively()
      assertEquals("COMPLETED", localSaver.save(USER, detail()).state)
      assertEquals(1, album.writeCalls)
    }
  }

  @Test
  fun staleDetailMappingYieldsToReceiptConfirmedMappingOnCleanupRetry() = runTest {
    withCache { cache, _ ->
      val album = FakeAlbum()
      val localSaver = saver(cache, album, FakeReceipts())
      val temporaryDirectory = File(requireNotNull(cache.fileForTesting(USER, MEDIA_ID)).parentFile, "$MEDIA_ID.tmp")
      check(temporaryDirectory.mkdirs())
      check(File(temporaryDirectory, "blocked").createNewFile())

      assertEquals("PRIVATE_MEDIA_CACHE_CLEANUP_FAILED", localSaver.save(USER, detail(STALE_MAPPING)).state)
      temporaryDirectory.deleteRecursively()
      assertEquals("COMPLETED", localSaver.save(USER, detail(STALE_MAPPING)).state)
      assertEquals(1, album.writeCalls)
    }
  }

  @Test
  fun receiptFailureWithRollbackFailureIsObservableAndRetainsCache() = runTest {
    withCache { cache, _ ->
      val receiptFailure = IOException("receipt failed")
      val album = FakeAlbum(deleteResult = false)
      val failure = assertFailsWith<IllegalStateException> {
        saver(cache, album, FakeReceipts(failure = receiptFailure)).save(USER, detail())
      }

      assertEquals(listOf(receiptFailure), failure.suppressed.toList())
      assertEquals(listOf("android:media-store:77"), album.deletedRefs)
      assertEquals(true, cache.get(USER, MEDIA_ID, CONTENT.size.toLong(), SHA256) != null)
    }
  }

  private fun saver(
    cache: PrivateOriginalDiskStore,
    album: FakeAlbum,
    receipts: FakeReceipts,
  ) = PrivateMediaLocalSaver(cache, album, receipts)

  private fun detail(localPlatformAssetRef: String? = null) = PrivateMediaDetail(
    id = MEDIA_ID,
    mediaType = "PHOTO",
    capturedAt = "2026-08-04T00:00:00Z",
    createdAt = "2026-08-04T00:00:01Z",
    width = 2,
    height = 2,
    durationMs = null,
    originalTotalSize = CONTENT.size.toLong(),
    resources = listOf(
      PrivateMediaResourceSummary(RESOURCE_ID, "ORIGINAL", "image/jpeg", CONTENT.size.toLong(), SHA256),
    ),
    localPlatformAssetRef = localPlatformAssetRef,
  )

  private suspend fun withCache(block: suspend (PrivateOriginalDiskStore, File) -> Unit) = withEmptyCache { cache, root ->
    val source = File(root, "source.jpg").apply { writeBytes(CONTENT) }
    check(cache.put(USER, MEDIA_ID, source, CONTENT.size.toLong(), SHA256) != null)
    block(cache, root)
  }

  private suspend fun withEmptyCache(block: suspend (PrivateOriginalDiskStore, File) -> Unit) {
    val root = Files.createTempDirectory("private-media-local-saver").toFile()
    try {
      block(PrivateOriginalDiskStore(File(root, "originals")), root)
    } finally {
      root.deleteRecursively()
    }
  }

  private class FakeAlbum(
    mappingPresent: Boolean = false,
    val events: MutableList<String> = mutableListOf(),
    private val deleteResult: Boolean = true,
  ) : SystemAlbumWriterPort {
    var writeCalls = 0
    val deletedRefs = mutableListOf<String>()
    private val presentRefs = mutableSetOf<String>().apply {
      if (mappingPresent) add(LOCAL_MAPPING)
    }

    override fun writeVerifiedMedia(request: SystemAlbumWriteRequest): SystemAlbumWriteResult {
      writeCalls += 1
      events += "write"
      presentRefs += "android:media-store:77"
      return SystemAlbumWriteResult("android:media-store:77")
    }

    override fun isSystemAlbumEntryPresent(platformAssetRef: String): Boolean = platformAssetRef in presentRefs

    override fun deleteSystemAlbumEntry(platformAssetRef: String): Boolean {
      deletedRefs += platformAssetRef
      return deleteResult
    }
  }

  private class FakeReceipts(
    private val failure: Throwable? = null,
    private val events: MutableList<String> = mutableListOf(),
  ) : PrivateMediaSaveReceiptRecorder {
    var calls = 0
    var platformAssetRef: String? = null

    override suspend fun record(
      mediaId: String,
      resourceId: String,
      platformAssetRef: String,
    ): PrivateMediaSaveResult {
      calls += 1
      this.platformAssetRef = platformAssetRef
      events += "receipt"
      failure?.let { throw it }
      return PrivateMediaSaveResult(mediaId, "COMPLETED", 1)
    }
  }

  private companion object {
    const val USER = "user-1"
    const val MEDIA_ID = "11111111-1111-4111-8111-111111111111"
    const val RESOURCE_ID = "22222222-2222-4222-8222-222222222222"
    const val LOCAL_MAPPING = "android:media-store:77"
    const val STALE_MAPPING = "android:media-store:66"
    val CONTENT = "image".toByteArray()
    val SHA256 = Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(CONTENT))
  }
}

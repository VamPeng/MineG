package com.mineg.mobile.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateThumbnailDiskCacheTest {
  @Test
  fun `one account media pair owns exactly one thumbnail file`() = withCache { root, cache ->
    val first = source(root, "first", ByteArray(24) { 1 })
    val second = source(root, "second", ByteArray(31) { 2 })

    val firstEntry = assertNotNull(cache.put(ACCOUNT, MEDIA, first, "THUMBNAIL", "image/jpeg"))
    val secondEntry = assertNotNull(cache.put(ACCOUNT, MEDIA, second, "THUMBNAIL", "image/jpeg"))

    assertEquals(firstEntry.file, secondEntry.file)
    assertContentEquals(second.readBytes(), assertNotNull(cache.get(ACCOUNT, MEDIA)).file.readBytes())
    assertEquals(1, root.walkTopDown().count { it.isFile && it.extension == "thumb" })
  }

  @Test
  fun `cache keys and files are isolated by account`() = withCache { root, cache ->
    val bytes = ByteArray(12) { 7 }
    val source = source(root, "shared", bytes)

    val first = assertNotNull(cache.put("account-a", MEDIA, source, "THUMBNAIL", "image/jpeg"))
    val second = assertNotNull(cache.put("account-b", MEDIA, source, "THUMBNAIL", "image/jpeg"))

    assertNotEquals(first.cacheKey, second.cacheKey)
    assertNotEquals(first.file, second.file)
    assertContentEquals(bytes, first.file.readBytes())
    assertContentEquals(bytes, second.file.readBytes())
  }

  @Test
  fun `corrupt cached bytes fail closed and are removed`() = withCache { root, cache ->
    val entry = assertNotNull(
      cache.put(ACCOUNT, MEDIA, source(root, "valid", ByteArray(20) { 3 }), "THUMBNAIL", "image/png"),
    )
    entry.file.writeBytes(ByteArray(20) { 4 })

    val restarted = PrivateThumbnailDiskCache(File(root, "cache"), maximumBytes = 1024, trimToBytes = 768)
    assertNull(restarted.get(ACCOUNT, MEDIA))
    assertFalse(entry.file.exists())
  }

  @Test
  fun `disk cache trims oldest entries to the low watermark`() = withCache(maximumBytes = 100, trimToBytes = 60) { root, cache ->
    val first = assertNotNull(cache.put(ACCOUNT, "media-1", source(root, "one", ByteArray(40) { 1 }), "THUMBNAIL", "image/jpeg"))
    first.file.setLastModified(1L)
    val second = assertNotNull(cache.put(ACCOUNT, "media-2", source(root, "two", ByteArray(40) { 2 }), "THUMBNAIL", "image/jpeg"))
    second.file.setLastModified(2L)
    val newest = assertNotNull(cache.put(ACCOUNT, "media-3", source(root, "three", ByteArray(40) { 3 }), "THUMBNAIL", "image/jpeg"))

    assertTrue(cache.currentSizeBytes() <= 60L)
    assertTrue(newest.file.exists())
    assertFalse(first.file.exists())
  }

  @Test
  fun `retained thumbnail is deleted after deferred invalidation and release`() = withCache { root, cache ->
    val entry = assertNotNull(
      cache.put(ACCOUNT, MEDIA, source(root, "retained", ByteArray(16) { 9 }), "THUMBNAIL", "image/jpeg"),
    )
    cache.retain(entry.cacheKey)

    cache.remove(ACCOUNT, MEDIA)
    assertTrue(entry.file.exists())
    assertNull(cache.get(ACCOUNT, MEDIA))

    cache.release(entry.cacheKey)
    assertFalse(entry.file.exists())
  }

  private fun withCache(
    maximumBytes: Long = 1024,
    trimToBytes: Long = 768,
    block: (File, PrivateThumbnailDiskCache) -> Unit,
  ) {
    val root = Files.createTempDirectory("mineg-thumbnail-cache-test").toFile()
    try {
      block(root, PrivateThumbnailDiskCache(File(root, "cache"), maximumBytes, trimToBytes))
    } finally {
      root.deleteRecursively()
    }
  }

  private fun source(root: File, name: String, bytes: ByteArray): File =
    File(root, "$name.source").also { it.writeBytes(bytes) }

  private companion object {
    const val ACCOUNT = "account-123"
    const val MEDIA = "media-456"
  }
}

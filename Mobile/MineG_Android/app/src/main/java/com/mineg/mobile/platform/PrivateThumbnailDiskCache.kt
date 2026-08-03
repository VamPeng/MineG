package com.mineg.mobile.platform

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

internal object PrivateThumbnailCacheKeys {
  private const val NAMESPACE = "private-thumbnail-v1"

  fun memoryKey(accountId: String, mediaId: String): String =
    "$NAMESPACE:${accountScope(accountId)}:${digestHex(mediaId)}"

  fun accountPrefix(accountId: String): String = "$NAMESPACE:${accountScope(accountId)}:"

  fun accountScope(accountId: String): String = digestHex(accountId)

  private fun digestHex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/**
 * Rebuildable, account-scoped cache for one verified thumbnail per cloud media item.
 *
 * The cache key never contains an OSS grant or object URL. Cache files live in the app-private
 * cache directory, are excluded from backup, and are committed with an adjacent integrity record.
 */
internal class PrivateThumbnailDiskCache(
  private val rootDirectory: File,
  private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
  private val trimToBytes: Long = DEFAULT_TRIM_TO_BYTES,
) {
  data class Entry(
    val cacheKey: String,
    val file: File,
    val resourceType: String,
    val mimeType: String,
    val byteLength: Long,
    val sha256Base64: String,
  )

  private data class Metadata(
    val resourceType: String,
    val mimeType: String,
    val byteLength: Long,
    val sha256Base64: String,
  )

  private val verifiedKeys = mutableSetOf<String>()
  private val retainedKeys = mutableMapOf<String, Int>()
  private val pendingRemovalKeys = mutableSetOf<String>()

  init {
    require(maximumBytes > 0L && trimToBytes in 0L..maximumBytes)
    check(rootDirectory.isDirectory || rootDirectory.mkdirs())
    cleanupIncompleteFiles()
    trimIfNeeded()
  }

  @Synchronized
  fun get(accountId: String, mediaId: String): Entry? {
    val cacheKey = PrivateThumbnailCacheKeys.memoryKey(accountId, mediaId)
    if (cacheKey in pendingRemovalKeys) return null
    val dataFile = dataFile(accountId, mediaId)
    val metadataFile = metadataFile(dataFile)
    val metadata = readMetadata(metadataFile)
    if (metadata == null || !dataFile.isFile || dataFile.length() != metadata.byteLength) {
      deleteEntry(cacheKey, dataFile, metadataFile)
      return null
    }
    if (cacheKey !in verifiedKeys && sha256Base64(dataFile) != metadata.sha256Base64) {
      deleteEntry(cacheKey, dataFile, metadataFile)
      return null
    }
    verifiedKeys += cacheKey
    val accessedAt = System.currentTimeMillis()
    dataFile.setLastModified(accessedAt)
    metadataFile.setLastModified(accessedAt)
    return Entry(
      cacheKey = cacheKey,
      file = dataFile,
      resourceType = metadata.resourceType,
      mimeType = metadata.mimeType,
      byteLength = metadata.byteLength,
      sha256Base64 = metadata.sha256Base64,
    )
  }

  @Synchronized
  fun put(
    accountId: String,
    mediaId: String,
    sourceFile: File,
    resourceType: String,
    mimeType: String,
  ): Entry? {
    if (!sourceFile.isFile || sourceFile.length() <= 0L ||
      !mimeType.matches(MIME_TYPE_PATTERN) || resourceType.isBlank()) return null
    val cacheKey = PrivateThumbnailCacheKeys.memoryKey(accountId, mediaId)
    val dataFile = dataFile(accountId, mediaId)
    val metadataFile = metadataFile(dataFile)
    check(dataFile.parentFile?.isDirectory == true || dataFile.parentFile?.mkdirs() == true)
    val dataTemp = File(dataFile.parentFile, dataFile.name + ".tmp")
    val metadataTemp = File(dataFile.parentFile, metadataFile.name + ".tmp")
    dataTemp.delete()
    metadataTemp.delete()
    return try {
      FileInputStream(sourceFile).use { input ->
        FileOutputStream(dataTemp).use { output ->
          input.copyTo(output)
          output.fd.sync()
        }
      }
      val digest = sha256Base64(dataTemp)
      val metadata = Metadata(resourceType, mimeType, dataTemp.length(), digest)
      writeMetadata(metadataTemp, metadata)
      replaceAtomically(dataTemp, dataFile)
      replaceAtomically(metadataTemp, metadataFile)
      val now = System.currentTimeMillis()
      dataFile.setLastModified(now)
      metadataFile.setLastModified(now)
      verifiedKeys += cacheKey
      pendingRemovalKeys -= cacheKey
      trimIfNeeded()
      Entry(cacheKey, dataFile, resourceType, mimeType, dataFile.length(), digest)
    } catch (_: Exception) {
      dataTemp.delete()
      metadataTemp.delete()
      null
    }
  }

  @Synchronized
  fun retain(cacheKey: String) {
    retainedKeys[cacheKey] = (retainedKeys[cacheKey] ?: 0) + 1
  }

  @Synchronized
  fun release(cacheKey: String) {
    val remaining = (retainedKeys[cacheKey] ?: return) - 1
    if (remaining > 0) {
      retainedKeys[cacheKey] = remaining
      return
    }
    retainedKeys.remove(cacheKey)
    if (pendingRemovalKeys.remove(cacheKey)) deleteEntryByKey(cacheKey)
  }

  @Synchronized
  fun remove(accountId: String, mediaId: String) {
    val cacheKey = PrivateThumbnailCacheKeys.memoryKey(accountId, mediaId)
    if ((retainedKeys[cacheKey] ?: 0) > 0) {
      pendingRemovalKeys += cacheKey
      verifiedKeys -= cacheKey
      return
    }
    deleteEntry(cacheKey, dataFile(accountId, mediaId), metadataFile(dataFile(accountId, mediaId)))
  }

  @Synchronized
  fun clearAccount(accountId: String) {
    val accountScope = PrivateThumbnailCacheKeys.accountScope(accountId)
    val directory = File(rootDirectory, accountScope)
    directory.listFiles().orEmpty()
      .filter { it.isFile && it.extension == DATA_EXTENSION }
      .forEach { file ->
        val cacheKey = cacheKey(accountScope, file.nameWithoutExtension)
        if ((retainedKeys[cacheKey] ?: 0) > 0) {
          pendingRemovalKeys += cacheKey
          verifiedKeys -= cacheKey
        } else {
          deleteEntry(cacheKey, file, metadataFile(file))
        }
      }
    directory.listFiles().orEmpty()
      .filter { it.isFile && it.extension == METADATA_EXTENSION && !dataFileForMetadata(it).exists() }
      .forEach(File::delete)
    if (directory.listFiles().isNullOrEmpty()) directory.delete()
  }

  @Synchronized
  internal fun currentSizeBytes(): Long = dataFiles().sumOf(File::length)

  private fun dataFile(accountId: String, mediaId: String): File {
    val accountScope = PrivateThumbnailCacheKeys.accountScope(accountId)
    val mediaHash = PrivateThumbnailCacheKeys.memoryKey(accountId, mediaId).substringAfterLast(':')
    return File(File(rootDirectory, accountScope), "$mediaHash.$DATA_EXTENSION")
  }

  private fun metadataFile(dataFile: File): File = File(dataFile.parentFile, dataFile.nameWithoutExtension + ".$METADATA_EXTENSION")

  private fun dataFileForMetadata(metadataFile: File): File =
    File(metadataFile.parentFile, metadataFile.nameWithoutExtension + ".$DATA_EXTENSION")

  private fun readMetadata(file: File): Metadata? = runCatching {
    if (!file.isFile) return null
    val properties = Properties().also { values -> FileInputStream(file).use(values::load) }
    val resourceType = properties.getProperty("resourceType")?.takeIf(String::isNotBlank) ?: return null
    val mimeType = properties.getProperty("mimeType")?.takeIf { it.matches(MIME_TYPE_PATTERN) } ?: return null
    val byteLength = properties.getProperty("byteLength")?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val digest = properties.getProperty("sha256Base64")?.takeIf(String::isNotBlank) ?: return null
    Metadata(resourceType, mimeType, byteLength, digest)
  }.getOrNull()

  private fun writeMetadata(file: File, metadata: Metadata) {
    val properties = Properties().apply {
      setProperty("resourceType", metadata.resourceType)
      setProperty("mimeType", metadata.mimeType)
      setProperty("byteLength", metadata.byteLength.toString())
      setProperty("sha256Base64", metadata.sha256Base64)
    }
    FileOutputStream(file).use { output ->
      properties.store(output, null)
      output.fd.sync()
    }
  }

  private fun replaceAtomically(source: File, target: File) {
    runCatching {
      Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }.getOrElse {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun sha256Base64(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
      }
    }
    return Base64.getEncoder().withoutPadding().encodeToString(digest.digest())
  }

  private fun cleanupIncompleteFiles() {
    rootDirectory.walkTopDown()
      .filter { it.isFile && (it.name.endsWith(".tmp") || it.extension == METADATA_EXTENSION && !dataFileForMetadata(it).exists()) }
      .forEach(File::delete)
    dataFiles().filter { !metadataFile(it).exists() }.forEach(File::delete)
  }

  private fun trimIfNeeded() {
    val files = dataFiles().sortedBy(File::lastModified)
    var total = files.sumOf(File::length)
    if (total <= maximumBytes) return
    for (file in files) {
      if (total <= trimToBytes) break
      val accountScope = file.parentFile?.name ?: continue
      val key = cacheKey(accountScope, file.nameWithoutExtension)
      if ((retainedKeys[key] ?: 0) > 0) continue
      val length = file.length()
      deleteEntry(key, file, metadataFile(file))
      total -= length
    }
  }

  private fun dataFiles(): List<File> = rootDirectory.walkTopDown()
    .filter { it.isFile && it.extension == DATA_EXTENSION }
    .toList()

  private fun deleteEntryByKey(cacheKey: String) {
    val parts = cacheKey.split(':')
    if (parts.size != 3 || parts[0] != "private-thumbnail-v1") return
    val file = File(File(rootDirectory, parts[1]), "${parts[2]}.$DATA_EXTENSION")
    deleteEntry(cacheKey, file, metadataFile(file))
  }

  private fun deleteEntry(cacheKey: String, dataFile: File, metadataFile: File) {
    verifiedKeys -= cacheKey
    pendingRemovalKeys -= cacheKey
    dataFile.delete()
    metadataFile.delete()
    val directory = dataFile.parentFile
    if (directory?.listFiles().isNullOrEmpty()) directory?.delete()
  }

  private fun cacheKey(accountScope: String, mediaHash: String): String =
    "private-thumbnail-v1:$accountScope:$mediaHash"

  companion object {
    private const val DATA_EXTENSION = "thumb"
    private const val METADATA_EXTENSION = "meta"
    private const val DEFAULT_MAXIMUM_BYTES = 256L * 1024L * 1024L
    private const val DEFAULT_TRIM_TO_BYTES = 192L * 1024L * 1024L
    private val MIME_TYPE_PATTERN = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
  }
}

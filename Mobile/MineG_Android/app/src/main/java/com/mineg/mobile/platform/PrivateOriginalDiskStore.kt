package com.mineg.mobile.platform

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/**
 * Durable, account-isolated storage for integrity-checked private-media originals.
 *
 * Each data filename is exactly the cloud media id. The surrounding account directory is hashed
 * so two accounts can never address the same file. No signed URL or object key is persisted.
 */
internal class PrivateOriginalDiskStore(private val rootDirectory: File) {
  init {
    check(rootDirectory.isDirectory || rootDirectory.mkdirs())
    cleanupIncompleteFiles()
  }

  @Synchronized
  fun get(
    accountId: String,
    mediaId: String,
    expectedSize: Long,
    expectedSha256Base64: String,
  ): File? {
    val file = dataFileOrNull(accountId, mediaId) ?: return null
    if (!isExpected(file, expectedSize, expectedSha256Base64)) {
      file.delete()
      return null
    }
    return file
  }

  @Synchronized
  fun put(
    accountId: String,
    mediaId: String,
    sourceFile: File,
    expectedSize: Long,
    expectedSha256Base64: String,
  ): File? {
    if (!sourceFile.isFile || expectedSize <= 0L || expectedSha256Base64.isBlank()) return null
    val target = dataFileOrNull(accountId, mediaId) ?: return null
    check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
    val temporary = File(target.parentFile, "${target.name}.tmp")
    temporary.delete()
    return try {
      FileInputStream(sourceFile).use { input ->
        FileOutputStream(temporary).use { output ->
          input.copyTo(output)
          output.fd.sync()
        }
      }
      if (!isExpected(temporary, expectedSize, expectedSha256Base64)) {
        temporary.delete()
        return null
      }
      replaceAtomically(temporary, target)
      target
    } catch (_: Exception) {
      temporary.delete()
      null
    }
  }

  @Synchronized
  fun remove(accountId: String, mediaId: String): Boolean {
    val file = dataFileOrNull(accountId, mediaId) ?: return false
    val temporary = File(file.parentFile, "${file.name}.tmp")
    file.delete()
    temporary.delete()
    val removed = !file.exists() && !temporary.exists()
    if (removed && file.parentFile?.listFiles().isNullOrEmpty()) file.parentFile?.delete()
    return removed
  }

  @Synchronized
  fun clearAccount(accountId: String) {
    val directory = accountDirectory(accountId)
    directory.listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
    if (directory.listFiles().isNullOrEmpty()) directory.delete()
  }

  internal fun fileForTesting(accountId: String, mediaId: String): File? =
    dataFileOrNull(accountId, mediaId)

  private fun dataFileOrNull(accountId: String, mediaId: String): File? {
    if (!mediaId.matches(MEDIA_ID_PATTERN)) return null
    return File(accountDirectory(accountId), mediaId)
  }

  private fun accountDirectory(accountId: String): File =
    File(rootDirectory, PrivateThumbnailCacheKeys.accountScope(accountId))

  private fun isExpected(file: File, expectedSize: Long, expectedDigest: String): Boolean =
    file.isFile && file.length() == expectedSize && sha256Base64(file) == expectedDigest

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

  private fun cleanupIncompleteFiles() {
    rootDirectory.walkTopDown()
      .filter { it.isFile && it.name.endsWith(".tmp") }
      .forEach(File::delete)
  }

  private companion object {
    val MEDIA_ID_PATTERN = Regex(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
    )
  }
}

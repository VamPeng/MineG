package com.mineg.mobile.platform

import android.content.Context
import com.mineg.mobile.contracts.FilePort
import java.io.File

class AndroidFilePort(private val context: Context) : FilePort {
  private val ciphertextDirectory: File
    get() = File(context.noBackupFilesDir, "mineg-ciphertext").also { check(it.isDirectory || it.mkdirs()) }

  override fun createEncryptedTempFile(name: String): String {
    require(name.matches(Regex("[A-Za-z0-9._-]{1,80}")))
    return File(ciphertextDirectory, "$name.mineg-cipher").absolutePath
  }

  override fun getAvailableSpace(): Long = ciphertextDirectory.usableSpace

  override fun deleteTempFile(path: String): Boolean {
    val target = File(path).canonicalFile
    val ciphertext = ciphertextDirectory.canonicalFile
    require(target.parentFile == ciphertext) { "Only MineG ciphertext files can be removed" }
    return !target.exists() || target.delete()
  }
}

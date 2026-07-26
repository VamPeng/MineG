package com.mineg.mobile.platform

import android.content.Context
import com.mineg.mobile.contracts.FilePort
import java.io.File

class AndroidFilePort(private val context: Context) : FilePort {
  override fun createEncryptedTempFile(name: String): String {
    require(name.matches(Regex("[A-Za-z0-9._-]{1,80}")))
    return File(context.cacheDir, "$name.mineg-cipher").absolutePath
  }

  override fun getAvailableSpace(): Long = context.cacheDir.usableSpace

  override fun deleteTempFile(path: String): Boolean {
    val target = File(path).canonicalFile
    val cache = context.cacheDir.canonicalFile
    require(target.parentFile == cache) { "Only MineG cache files can be removed" }
    return !target.exists() || target.delete()
  }
}

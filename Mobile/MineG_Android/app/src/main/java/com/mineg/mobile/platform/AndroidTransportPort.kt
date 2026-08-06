/** HTTP and object-transfer implementation of the Core transport port. */
package com.mineg.mobile.platform

import com.mineg.mobile.platform.port.ApiRequest
import com.mineg.mobile.platform.port.ApiResponse
import com.mineg.mobile.platform.port.DownloadObjectRequest
import com.mineg.mobile.platform.port.DownloadObjectResult
import com.mineg.mobile.platform.port.TransportPort
import com.mineg.mobile.platform.port.UploadPartRequest
import com.mineg.mobile.platform.port.UploadPartResult
import com.mineg.mobile.platform.port.UploadObjectRequest
import com.mineg.mobile.platform.port.UploadObjectResult
import java.io.RandomAccessFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Enforces base-host, redirect and size constraints before performing network I/O. */
class AndroidTransportPort(
  private val apiBaseUrl: String,
  allowPrivateHttp: Boolean = false,
) : TransportPort {
  private val baseUri = URI(apiBaseUrl).also {
    val acceptedScheme = it.scheme == "https" ||
      (allowPrivateHttp && it.scheme == "http" && isPrivateDevelopmentHost(it.host.orEmpty()))
    require(
      acceptedScheme && it.host != null && it.userInfo == null && it.query == null && it.fragment == null &&
        (it.path.isNullOrEmpty() || it.path == "/"),
    ) {
      "API base URL must be credential-free HTTPS or explicitly allowed private-network HTTP"
    }
  }

  override suspend fun sendApiRequest(request: ApiRequest): ApiResponse = withContext(Dispatchers.IO) {
    require(request.path.startsWith('/') && !request.path.startsWith("//"))
    val connection = URL(apiBaseUrl.trimEnd('/') + request.path).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = request.method
      connection.connectTimeout = 10_000
      connection.readTimeout = 15_000
      connection.instanceFollowRedirects = false
      connection.setRequestProperty("Accept", "application/json, application/problem+json")
      request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
      request.body?.let { body ->
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body) }
      }
      val body = (if (connection.responseCode >= 400) connection.errorStream else connection.inputStream)
        ?.use { it.readBytes() }
        ?: ByteArray(0)
      ApiResponse(
        status = connection.responseCode,
        contentType = connection.contentType.orEmpty(),
        requestId = connection.getHeaderField("X-Request-ID"),
        body = body,
        retryAfterSeconds = connection.getHeaderField("Retry-After")
          ?.trim()
          ?.toLongOrNull()
          ?.coerceIn(0L, 15 * 60L),
      )
    } finally {
      connection.disconnect()
    }
  }

  override suspend fun uploadPart(request: UploadPartRequest): UploadPartResult = withContext(Dispatchers.IO) {
    require(request.method == "PUT" && request.offset >= 0 && request.size in 1..4L * 1024 * 1024 + 16)
    val target = URI(request.url)
    require(target.scheme == "https" && target.userInfo == null && target.host != null)
    val connection = target.toURL().openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "PUT"
      connection.connectTimeout = 15_000
      connection.readTimeout = 60_000
      connection.instanceFollowRedirects = false
      connection.doOutput = true
      connection.setFixedLengthStreamingMode(request.size)
      request.headers.forEach { (name, value) ->
        if (!name.equals("Host", true) && !name.equals("Content-Length", true)) {
          connection.setRequestProperty(name, value)
        }
      }
      request.sourceDescriptor?.let { descriptor ->
        FileInputStream(File("/proc/self/fd/$descriptor")).use { input ->
          input.channel.position(request.offset)
          connection.outputStream.use { output ->
            copyPart(input::read, output, request.size)
          }
        }
      } ?: run {
        RandomAccessFile(requireNotNull(request.sourcePath) { "upload source is missing" }, "r").use { input ->
          input.seek(request.offset)
          connection.outputStream.use { output ->
            copyPart(input::read, output, request.size)
          }
        }
      }
      val status = connection.responseCode
      if (status !in 200..299) throw java.io.IOException("media part upload failed with status $status")
      val etag = connection.getHeaderField("ETag")?.trim()?.trim('"').orEmpty()
      check(etag.isNotBlank()) { "media part response omitted ETag" }
      UploadPartResult(etag)
    } finally {
      connection.disconnect()
    }
  }

  /** Copies exactly one requested file or descriptor range into an upload connection. */
  private fun copyPart(
    read: (ByteArray, Int, Int) -> Int,
    output: java.io.OutputStream,
    size: Long,
  ) {
    val buffer = ByteArray(64 * 1024)
    try {
      var remaining = size
      while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(count > 0) { "media part is truncated" }
        output.write(buffer, 0, count)
        remaining -= count
      }
    } finally {
      buffer.fill(0)
    }
  }

  override suspend fun uploadObject(request: UploadObjectRequest): UploadObjectResult = withContext(Dispatchers.IO) {
    require(request.method == "PUT" && request.body.size in 1..10 * 1024 * 1024)
    val target = URI(request.url)
    require(target.scheme == "https" && target.userInfo == null && target.host != null)
    val connection = target.toURL().openConnection() as HttpURLConnection
    try {
      connection.requestMethod = request.method
      connection.connectTimeout = 15_000
      connection.readTimeout = 60_000
      connection.instanceFollowRedirects = false
      connection.doOutput = true
      connection.setFixedLengthStreamingMode(request.body.size)
      request.headers.forEach { (name, value) ->
        if (!name.equals("Host", true) && !name.equals("Content-Length", true)) {
          connection.setRequestProperty(name, value)
        }
      }
      connection.outputStream.use { it.write(request.body) }
      val status = connection.responseCode
      if (status !in 200..299) throw java.io.IOException("object upload failed with status $status")
      UploadObjectResult(status)
    } finally {
      connection.disconnect()
    }
  }

  override suspend fun downloadObject(request: DownloadObjectRequest): DownloadObjectResult = withContext(Dispatchers.IO) {
    require(request.method == "GET" && request.maximumSize > 0 &&
      (request.expectedSize == null || request.expectedSize in 1..request.maximumSize)) {
      "invalid object download request"
    }
    val target = URI(request.url)
    require(target.scheme == "https" && target.userInfo == null && target.host != null) {
      "object download URL must be credential-free HTTPS"
    }
    val destination = File(request.destinationPath)
    require(destination.parentFile?.isDirectory == true && !destination.isDirectory) {
      "object download destination is unavailable"
    }
    val connection = target.toURL().openConnection() as HttpURLConnection
    var completed = false
    try {
      connection.requestMethod = "GET"
      connection.connectTimeout = 15_000
      connection.readTimeout = 60_000
      connection.instanceFollowRedirects = false
      request.headers.forEach { (name, value) ->
        if (!name.equals("Host", true) && !name.equals("Content-Length", true)) {
          connection.setRequestProperty(name, value)
        }
      }
      val status = connection.responseCode
      if (status !in 200..299) throw IOException("object download failed with status $status")
      val announcedLength = connection.contentLengthLong
      if (request.expectedSize != null && announcedLength >= 0 && announcedLength != request.expectedSize) {
        throw IOException("object download size does not match manifest")
      }
      if (announcedLength > request.maximumSize) throw IOException("object download exceeds limit")
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(64 * 1024)
      var bytesWritten = 0L
      try {
        connection.inputStream.use { input ->
          FileOutputStream(destination, false).use { output ->
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              if (count == 0) continue
              bytesWritten += count
              if (bytesWritten > request.maximumSize) throw IOException("object download exceeds limit")
              digest.update(buffer, 0, count)
              output.write(buffer, 0, count)
            }
            output.fd.sync()
          }
        }
      } finally {
        buffer.fill(0)
      }
      if (request.expectedSize != null && bytesWritten != request.expectedSize) {
        throw IOException("object download is truncated")
      }
      completed = true
      DownloadObjectResult(
        status = status,
        bytesWritten = bytesWritten,
        sha256Base64 = Base64.getEncoder().withoutPadding().encodeToString(digest.digest()),
        contentType = connection.contentType?.substringBefore(';')?.trim().orEmpty(),
      )
    } finally {
      connection.disconnect()
      if (!completed) destination.delete()
    }
  }

  /** Identifies loopback/private hosts permitted only by the explicit debug option. */
  private fun isPrivateDevelopmentHost(host: String): Boolean {
    if (host == "localhost" || host == "::1" || host == "[::1]") return true
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
      octets[0] == 127 ||
      (octets[0] == 172 && octets[1] in 16..31) ||
      (octets[0] == 192 && octets[1] == 168)
  }
}

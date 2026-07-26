package com.mineg.mobile.platform

import com.mineg.mobile.contracts.ApiRequest
import com.mineg.mobile.contracts.ApiResponse
import com.mineg.mobile.contracts.TransportPort
import com.mineg.mobile.contracts.UploadPartRequest
import com.mineg.mobile.contracts.UploadPartResult
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
      RandomAccessFile(request.ciphertextPath, "r").use { input ->
        input.seek(request.offset)
        connection.outputStream.use { output ->
          val buffer = ByteArray(64 * 1024)
          var remaining = request.size
          while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(count > 0) { "ciphertext part is truncated" }
            output.write(buffer, 0, count)
            remaining -= count
          }
          buffer.fill(0)
        }
      }
      val status = connection.responseCode
      if (status !in 200..299) throw java.io.IOException("ciphertext part upload failed with status $status")
      val etag = connection.getHeaderField("ETag")?.trim()?.trim('"').orEmpty()
      check(etag.isNotBlank()) { "ciphertext part response omitted ETag" }
      UploadPartResult(etag)
    } finally {
      connection.disconnect()
    }
  }

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

package com.mineg.mobile.platform

import com.mineg.mobile.contracts.ApiRequest
import com.mineg.mobile.contracts.ApiResponse
import com.mineg.mobile.contracts.TransportPort
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

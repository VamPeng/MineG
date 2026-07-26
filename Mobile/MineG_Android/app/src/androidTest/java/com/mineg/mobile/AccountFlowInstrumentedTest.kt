package com.mineg.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AccountFlowInstrumentedTest {
  @get:Rule
  val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun liveRegistrationBootstrapsFamilyKeyAndEntersProfile() {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue(arguments.getString("minegRunLiveAccountFlow") == "true")
    val phone = checkNotNull(arguments.getString("minegTestPhone"))
    val password = checkNotNull(arguments.getString("minegTestPassword"))

    compose.waitUntilAtLeastOneExists(hasTestTag("auth.login"), 20_000)
    compose.onNodeWithTag("auth.login.openSignup").performClick()
    compose.onNodeWithTag("auth.signup").assertIsDisplayed()
    compose.onNodeWithTag("auth.signup.phone").performTextInput(phone)
    compose.onNodeWithTag("auth.signup.password").performTextInput(password)
    compose.onNodeWithTag("auth.signup.passwordConfirmation").performTextInput(password)
    compose.onNodeWithTag("auth.signup.submit").performClick()

    compose.waitUntilAtLeastOneExists(hasTestTag("auth.reviewPending"), 30_000)
    compose.onNodeWithTag("auth.reviewPending").assertIsDisplayed()

    approveOnlyPendingApplication(
      baseUrl = arguments.getString("minegTestApiBaseUrl") ?: "http://127.0.0.1:8080",
      username = checkNotNull(arguments.getString("minegAdminUsername")),
      password = checkNotNull(arguments.getString("minegAdminPassword")),
    )
    compose.onNodeWithTag("auth.reviewPending.refresh").performClick()
    compose.waitUntilAtLeastOneExists(hasTestTag("profile.home"), 30_000)
    compose.onNodeWithTag("profile.home").assertIsDisplayed()

    compose.onNodeWithTag("profile.home.signOut").performClick()
    compose.onNodeWithText("确认退出").performClick()
    compose.waitUntilAtLeastOneExists(hasTestTag("auth.login"), 20_000)
    compose.onNodeWithTag("auth.login.phone").performTextInput(phone)
    compose.onNodeWithTag("auth.login.password").performTextInput(password)
    compose.onNodeWithTag("auth.login.agreement").performClick()
    compose.onNodeWithTag("auth.login.submit").performClick()
    compose.waitUntilAtLeastOneExists(hasTestTag("profile.home"), 30_000)
    compose.onNodeWithTag("profile.home").assertIsDisplayed()
    compose.onNodeWithTag("profile.home.openBackup").performClick()
    compose.waitUntilAtLeastOneExists(hasTestTag("permission.library"), 10_000)
    compose.onNodeWithTag("permission.library").assertIsDisplayed()
  }

  private fun approveOnlyPendingApplication(baseUrl: String, username: String, password: String) {
    val login = request(
      url = "$baseUrl/api/v1/admin/login",
      method = "POST",
      origin = "http://localhost:5173",
      body = JSONObject().put("username", username).put("password", password).toString(),
    )
    check(login.status == 200) { "admin login failed: ${login.status} ${login.body}" }
    val cookie = checkNotNull(login.setCookie).substringBefore(';')
    val csrf = JSONObject(login.body).getString("csrf_token")
    val page = request("$baseUrl/api/v1/admin/approvals?limit=20", cookie = cookie)
    check(page.status == 200) { "approval list failed: ${page.status} ${page.body}" }
    val items = JSONObject(page.body).getJSONArray("items")
    check(items.length() == 1) { "expected one pending application, got ${items.length()}" }
    val approvalID = items.getJSONObject(0).getString("id")
    val approved = request(
      url = "$baseUrl/api/v1/admin/approvals/$approvalID/approve",
      method = "POST",
      origin = "http://localhost:5173",
      cookie = cookie,
      headers = mapOf("X-CSRF-Token" to csrf, "Idempotency-Key" to UUID.randomUUID().toString()),
    )
    check(approved.status == 200) { "approve failed: ${approved.status} ${approved.body}" }
    check(JSONObject(approved.body).getString("outcome") == "APPROVED")
  }

  private fun request(
    url: String,
    method: String = "GET",
    origin: String? = null,
    cookie: String? = null,
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
  ): HTTPResult {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = method
      connection.connectTimeout = 10_000
      connection.readTimeout = 15_000
      connection.setRequestProperty("Accept", "application/json, application/problem+json")
      origin?.let { connection.setRequestProperty("Origin", it) }
      cookie?.let { connection.setRequestProperty("Cookie", it) }
      headers.forEach(connection::setRequestProperty)
      body?.let {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { output -> output.write(it.toByteArray()) }
      }
      val status = connection.responseCode
      val responseBody = (if (status >= 400) connection.errorStream else connection.inputStream)
        ?.bufferedReader()
        ?.use { it.readText() }
        .orEmpty()
      return HTTPResult(status, responseBody, connection.getHeaderField("Set-Cookie"))
    } finally {
      connection.disconnect()
    }
  }

  private data class HTTPResult(val status: Int, val body: String, val setCookie: String?)
}

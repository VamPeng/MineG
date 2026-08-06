package com.mineg.mobile

import com.mineg.mobile.bridge.account.AccountCoreGateway
import java.lang.reflect.Modifier
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountV3ContractTest {
  private val manifest by lazy {
    checkNotNull(javaClass.classLoader?.getResourceAsStream("account-v3.json"))
      .bufferedReader()
      .use { it.readText() }
  }

  @Test
  fun accountV3RemovesMediaKeysFromAdmissionAndKeepsDomainOwnershipInCore() {
    listOf(
      "AccountSignUp",
      "AccountSignIn",
      "AccountRestoreSession",
      "AccountSignOut",
      "AccountRefreshReviewStatus",
      "ProfileGetCurrent",
      "ProfileUpdateCurrent",
      "readSecrets",
      "writeSecrets",
      "deleteSecrets",
      "Registration does not create or submit a user key bundle",
      "Administrator approval transitions the account directly to APPROVED",
    ).forEach { assertContains(manifest, "\"$it\"") }
    assertContains(manifest, "Passwords and tokens are never persisted in Core SQLite")
    val contract = JSONObject(manifest)
    val commands = contract.getJSONArray("commands").let { values ->
      (0 until values.length()).map(values::getString).toSet()
    }
    val registrationFields = contract.getJSONArray("registrationFields").let { values ->
      (0 until values.length()).map(values::getString)
    }
    assertFalse("CoordinateFamilyKeyGrants" in commands)
    assertEquals(listOf("phone", "password", "device_installation_id", "platform"), registrationFields)
  }

  @Test
  fun AndroidAccountBridgeOnlyPublishesFrozenBusinessMethods() {
    val methods = AccountCoreGateway::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertTrue(
      methods.containsAll(
        setOf("signUp", "signIn", "signOut", "restoreSession", "refreshReviewStatus", "getProfile", "updateProfile"),
      ),
    )
  }
}

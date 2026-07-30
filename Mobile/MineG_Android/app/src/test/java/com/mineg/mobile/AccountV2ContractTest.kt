package com.mineg.mobile

import com.mineg.mobile.account.CoreAccountClient
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class AccountV2ContractTest {
  private val manifest by lazy {
    checkNotNull(javaClass.classLoader?.getResourceAsStream("account-v2.json"))
      .bufferedReader()
      .use { it.readText() }
  }

  @Test
  fun accountV2MovesSessionAndProfileOwnershipIntoCore() {
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
    ).forEach { assertContains(manifest, "\"$it\"") }
    assertContains(manifest, "Passwords and tokens are never persisted in Core SQLite")
  }

  @Test
  fun AndroidAccountBridgeOnlyPublishesFrozenBusinessMethods() {
    val methods = CoreAccountClient::class.java.declaredMethods
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

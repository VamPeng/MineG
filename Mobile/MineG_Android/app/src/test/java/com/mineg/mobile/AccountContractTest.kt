package com.mineg.mobile

import com.mineg.mobile.feature.auth.validation.AccountInputValidator
import com.mineg.mobile.bridge.account.AccountClient
import com.mineg.mobile.core.CoreClient
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountContractTest {
  private val manifest: String by lazy { resource("account-v1.json") }

  @Test
  fun frozenManifestRegistersTheCompleteM1Surface() {
    listOf(
      "signUp", "signIn", "signOut", "restoreSession", "refreshReviewStatus", "getProfile",
      "PersistAccountState", "ClearAccountState", "GetAccountState", "AccountStateChanged",
      "auth.login", "auth.signup", "auth.reviewPending", "profile.home",
      "PHONE_ALREADY_REGISTERED", "SESSION_REPLAYED", "AGREEMENT_REQUIRED",
    ).forEach { assertContains(manifest, "\"$it\"") }
    assertContains(manifest, "\"status\": \"FROZEN\"")
  }

  @Test
  fun accountClientAndCoreExposeRegisteredMethodsWithoutPlatformTypes() {
    val accountMethods = AccountClient::class.java.methods.map { it.name }.toSet()
    assertTrue(accountMethods.containsAll(setOf("signUp", "signIn", "signOut", "restoreSession", "refreshReviewStatus", "getProfile")))
    AccountClient::class.java.methods
      .flatMap { it.parameterTypes.asList() + it.returnType }
      .forEach {
        assertFalse(it.name.startsWith("android."))
        assertFalse(it.name.startsWith("androidx.compose."))
      }
    val coreMethods = CoreClient::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertFalse(coreMethods.contains("createUserKeyBundle"))
    assertFalse(coreMethods.contains("createFamilyKeyEnvelope"))
  }

  @Test
  fun sqliteMigrationContainsOnlyNonSensitiveAccountState() {
    val migration = resource("002_account_state.sql")
    listOf("user_id", "masked_phone", "approval_status", "updated_at").forEach {
      assertContains(migration, it)
    }
    listOf("access_token", "refresh_token", "private_key", "password").forEach {
      assertFalse(migration.contains(it, ignoreCase = true), "migration leaks forbidden field $it")
    }
  }

  @Test
  fun mainlandPhoneAndPasswordRulesMatchTheContract() {
    assertEquals("+8613800138000", AccountInputValidator.normalizePhone("13800138000"))
    assertEquals("+8613800138000", AccountInputValidator.normalizePhone("+8613800138000"))
    assertEquals("138****8000", AccountInputValidator.maskedPhone("13800138000"))
    assertNull(AccountInputValidator.normalizePhone("12800138000"))
    assertNull(AccountInputValidator.passwordError("family2026"))
    assertTrue(AccountInputValidator.passwordError("onlyletters") != null)
    assertTrue(AccountInputValidator.passwordError("12345678") != null)
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}

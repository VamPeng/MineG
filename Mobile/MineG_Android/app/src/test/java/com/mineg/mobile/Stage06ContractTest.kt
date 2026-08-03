package com.mineg.mobile

import com.mineg.mobile.account.CoreStage06Client
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage06ContractTest {
  @Test
  fun baselineKeepsFamilyTrashAndFeedbackAuthorityInCore() {
    val contract = resource("stage06-v1.json")
    listOf(
      "stage06-v1",
      "C++ Core",
      "SetPrivateMediaShare",
      "RefreshFamilyMedia",
      "RefreshTrashMedia",
      "RestoreTrashMedia",
      "SubmitFeedback",
      "VIEW",
      "STREAM",
      "DOWNLOAD",
    ).forEach { assertContains(contract, "\"$it\"") }
    assertContains(contract, "\"usesMediaKeyOrEnvelope\": false")
    assertFalse(contract.contains("Media Key"))
  }

  @Test
  fun androidStage06ClientExposesOnlyTypedCoreOperations() {
    val publicMethods = CoreStage06Client::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertTrue(publicMethods.containsAll(setOf(
      "setPrivateMediaShare",
      "refreshFamilyMedia",
      "getFamilyMediaDetail",
      "openFamilyMedia",
      "closeFamilyMedia",
      "refreshTrash",
      "restoreTrash",
      "sendFeedback",
    )))
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}

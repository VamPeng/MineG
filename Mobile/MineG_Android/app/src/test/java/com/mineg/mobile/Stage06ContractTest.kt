package com.mineg.mobile

import com.mineg.mobile.bridge.feedback.FeedbackCoreGateway
import com.mineg.mobile.bridge.shared.SharedMediaCoreGateway
import com.mineg.mobile.bridge.trash.TrashCoreGateway
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage06ContractTest {
  @Test
  fun baselineKeepsSharedTrashAndFeedbackAuthorityInCore() {
    val contract = resource("stage06-v1.json")
    listOf(
      "stage06-v1",
      "C++ Core",
      "SetPrivateMediaShare",
      "RefreshSharedMedia",
      "RefreshTrashMedia",
      "RestoreTrashMedia",
      "SubmitFeedback",
      "VIEW",
      "STREAM",
      "DOWNLOAD",
    ).forEach { assertContains(contract, "\"$it\"") }
    assertContains(contract, "\"usesMediaKeyOrEnvelope\": false")
    assertContains(contract, "\"sharedSpaceModel\": \"ALL_APPROVED_USERS\"")
    assertFalse(contract.contains("membershipRequired"))
    assertFalse(contract.contains("Media Key"))
  }

  @Test
  fun androidResponsibilityGatewaysExposeOnlyTypedCoreOperations() {
    val publicMethods = listOf(
      SharedMediaCoreGateway::class.java,
      TrashCoreGateway::class.java,
      FeedbackCoreGateway::class.java,
    ).flatMap { type ->
      type.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) }
        .map { it.name }
    }.toSet()
    assertTrue(publicMethods.containsAll(setOf(
      "setPrivateMediaShare",
      "refreshSharedMedia",
      "getSharedMediaDetail",
      "openSharedMedia",
      "closeSharedMedia",
      "refreshTrash",
      "restoreTrash",
      "sendFeedback",
    )))
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}

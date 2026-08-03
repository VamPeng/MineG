package com.mineg.mobile

import com.mineg.mobile.account.CoreStage02Client
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage02ContractTest {
  @Test
  fun activeStage02ClientExcludesRetiredFamilyKeyCoordination() {
    val methods = CoreStage02Client::class.java.methods.map { it.name }.toSet()
    assertTrue(methods.contains("listPrivateMedia"))
    assertFalse(methods.contains("coordinateFamilyKeyGrants"))
  }
}

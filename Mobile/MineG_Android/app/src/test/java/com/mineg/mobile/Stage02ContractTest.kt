package com.mineg.mobile

import com.mineg.mobile.bridge.media.PrivateMediaCoreGateway
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage02ContractTest {
  @Test
  fun privateMediaGatewayExcludesRetiredFamilyKeyCoordination() {
    val methods = PrivateMediaCoreGateway::class.java.methods.map { it.name }.toSet()
    assertTrue(methods.contains("refreshPrivateMedia"))
    assertFalse(methods.contains("coordinateFamilyKeyGrants"))
  }
}

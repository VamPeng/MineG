package com.mineg.mobile

import com.mineg.mobile.core.CoreClient
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage03ContractTest {
  @Test
  fun coreNoLongerExposesLegacyEncryptionOrCiphertextRecovery() {
    val methods = CoreClient::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    listOf(
      "createMediaKeyEnvelope", "computeDedupeFingerprint", "encryptMediaResource",
      "encryptMediaManifest", "decryptMediaResource", "randomKey",
    ).forEach { assertFalse(methods.contains(it)) }
  }
}

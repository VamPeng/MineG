package com.mineg.mobile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mineg.mobile.core.CoreClient
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreLifecycleInstrumentedTest {
  @Test
  fun repeatedCreateSubscribeExecuteReleaseAndReopen() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val database = File(context.filesDir, "instrumented-core.db")
    repeat(25) { iteration ->
      CoreClient().use { core ->
        core.initialize(database.absolutePath)
        var event = ""
        val subscription = core.subscribe { event = it }
        core.execute(
          iteration + 1L,
          "{\"version\":1,\"type\":\"FoundationWriteProbe\",\"value\":\"iteration-$iteration\"}",
        )
        assertTrue(event.contains("FoundationProbeChanged"))
        core.unsubscribe(subscription)
      }
    }
    CoreClient().use { core ->
      core.initialize(database.absolutePath)
      assertTrue(core.query("{\"version\":1,\"type\":\"FoundationReadProbe\"}").contains("iteration-24"))
      val password = "family-photo-2026".toByteArray()
      try {
        val bundle = core.createUserKeyBundle(password)
        assertTrue(bundle.publicKey.size == 32)
        assertTrue(bundle.encryptedKeyBundle.size == 128)
        assertTrue(bundle.kdfParametersJson.contains("ARGON2ID13"))
        val deviceWrapKey = core.randomKey()
        val unlockBlob = core.unlockUserKeyBundle(password, bundle.publicKey, bundle.encryptedKeyBundle, deviceWrapKey)
        assertTrue(unlockBlob.size == 112)
        val familyEnvelope = core.createFamilyKeyEnvelope(bundle.publicKey, bootstrapIfNeeded = true)
        assertTrue(familyEnvelope.size == 80)
        core.lockKeys()
        core.restoreUserKeyBundle(bundle.publicKey, deviceWrapKey, unlockBlob)
        core.unlockFamilyKeyEnvelope(familyEnvelope)
        deviceWrapKey.fill(0)
        unlockBlob.fill(0)
        familyEnvelope.fill(0)
        bundle.publicKey.fill(0)
        bundle.encryptedKeyBundle.fill(0)
      } finally {
        password.fill(0)
      }
    }
  }
}

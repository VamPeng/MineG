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
    }
  }
}

package com.mineg.mobile

import com.mineg.mobile.contracts.BackgroundSchedulerPort
import com.mineg.mobile.contracts.ConnectivityPort
import com.mineg.mobile.contracts.FilePort
import com.mineg.mobile.contracts.MediaPlaybackPort
import com.mineg.mobile.contracts.MediaSourcePort
import com.mineg.mobile.contracts.SecureStorePort
import com.mineg.mobile.contracts.SystemAlbumWriterPort
import com.mineg.mobile.contracts.TransportPort
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.platform.AndroidTransportPort
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoundationContractTest {
  private val manifest: String by lazy {
    checkNotNull(javaClass.classLoader?.getResourceAsStream("foundation-v1.json"))
      .bufferedReader()
      .use { it.readText() }
  }

  @Test
  fun frozenManifestContainsRequiredNames() {
    listOf(
      "initialize", "execute", "query", "subscribe", "unsubscribe", "cancel", "close",
      "FoundationWriteProbe", "FoundationReadProbe", "FoundationProbeChanged",
      "foundation.probe", "foundation.probe.run", "foundation.probe.status",
    ).forEach { assertContains(manifest, "\"$it\"") }
    assertContains(manifest, "\"status\": \"FROZEN\"")
  }

  @Test
  fun coreClientPublicSurfaceMatchesFrozenContract() {
    val names = CoreClient::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertTrue(names.containsAll(setOf("initialize", "execute", "query", "subscribe", "unsubscribe", "cancel", "close")))
  }

  @Test
  fun platformPortNamesMatchFrozenContractAndExposeNoAndroidTypes() {
    val ports = listOf(
      MediaSourcePort::class.java,
      SecureStorePort::class.java,
      TransportPort::class.java,
      BackgroundSchedulerPort::class.java,
      ConnectivityPort::class.java,
      FilePort::class.java,
      MediaPlaybackPort::class.java,
      SystemAlbumWriterPort::class.java,
    )
    assertEquals(8, ports.size)
    ports.forEach { port ->
      assertContains(manifest, "\"${port.simpleName}\"")
      port.methods.flatMap { it.parameterTypes.asList() + it.returnType }.forEach { type ->
        assertFalse(type.name.startsWith("android."), "${port.simpleName} exposes ${type.name}")
        assertFalse(type.name.startsWith("androidx.compose."), "${port.simpleName} exposes ${type.name}")
      }
    }
  }

  @Test
  fun transportAllowsCleartextOnlyForExplicitPrivateNetworkDebugUse() {
    AndroidTransportPort("http://192.168.1.6:8080", allowPrivateHttp = true)
    AndroidTransportPort("http://10.0.2.2:8080", allowPrivateHttp = true)
    AndroidTransportPort("https://api.example.test")

    assertFailsWith<IllegalArgumentException> {
      AndroidTransportPort("http://192.168.1.6:8080")
    }
    assertFailsWith<IllegalArgumentException> {
      AndroidTransportPort("http://8.8.8.8:8080", allowPrivateHttp = true)
    }
    assertFailsWith<IllegalArgumentException> {
      AndroidTransportPort("http://user:secret@192.168.1.6:8080", allowPrivateHttp = true)
    }
  }
}

package com.mineg.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage03V2ContractTest {
  @Test
  fun currentUploadContractUsesOriginalMediaWithoutClientEncryption() {
    val contract = JSONObject(
      checkNotNull(javaClass.classLoader?.getResourceAsStream("stage03-v2.json"))
        .bufferedReader().use { it.readText() },
    )
    assertEquals("stage03-v2", contract.getString("contractVersion"))
    assertFalse(contract.getJSONObject("architecture").getBoolean("clientMediaEncryption"))
    assertEquals("PUBLIC_ECS", contract.getJSONObject("architecture").getString("service"))
    assertEquals("MEDIA_ORIGINAL", contract.getJSONObject("upload").getString("purpose"))
    assertEquals(4 * 1024 * 1024, contract.getJSONObject("upload").getInt("partSize"))
    assertFalse(contract.has("legacyCompatibility"))
    val forbidden = contract.getJSONObject("upload").getJSONArray("forbiddenRequestFields")
    assertTrue((0 until forbidden.length()).map(forbidden::getString).contains("encrypted_media_key"))
  }
}

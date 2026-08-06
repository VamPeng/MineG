package com.mineg.mobile

import com.mineg.mobile.bridge.media.PrivateMediaCoreGateway
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.lang.reflect.Modifier

class Stage05ContractTest {
  @Test
  fun baselineKeepsPrivateMediaOwnershipAndShortLivedObjectGrantsInCore() {
    val contract = resource("stage05-v1.json")
    listOf(
      "stage05-v1", "FROZEN", "C++ Core", "RefreshPrivateMedia",
      "OpenPrivateMedia", "ClosePrivateMedia", "RecordPrivateMediaSystemSave", "TrashPrivateMedia", "VIEW",
      "maxGrantLifetimeSeconds",
    ).forEach { assertContains(contract, "\"$it\"") }
    assertContains(contract, "ORIGINAL_RESOURCE downloads are checked")
    assertFalse(contract.contains("Media Key"))
  }

  @Test
  fun sqliteMigrationPersistsOnlyDurablePrivateMediaTruth() {
    val migration = resource("013_private_media_stage05.sql")
    listOf(
      "private_media_items_v2", "private_media_page_state_v2", "private_media_resources",
      "download_receipts", "content_revision", "resource_set_digest", "PRAGMA user_version=13",
    ).forEach { assertContains(migration, it) }
    assertTrue(!migration.contains("signed_url") && !migration.contains("object_key"))
  }

  @Test
  fun androidExposesOnlyCoreOwnedPageOperations() {
    val publicMethods = PrivateMediaCoreGateway::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertTrue(publicMethods.containsAll(setOf(
      "refreshPrivateMedia", "loadMorePrivateMedia", "getPrivateMediaPage",
      "getPrivateMediaDetail", "openPrivateMedia", "closePrivateMedia", "trashPrivateMedia", "record",
    )))
    assertTrue(publicMethods.none {
      it == "savePrivateMediaToSystemAlbum" || it == "retryPrivateMediaSave" ||
        it == "cancelPrivateMediaSave" || it == "getPrivateMediaSaveOperation"
    })
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}

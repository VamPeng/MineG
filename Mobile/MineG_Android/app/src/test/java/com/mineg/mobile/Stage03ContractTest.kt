package com.mineg.mobile

import com.mineg.mobile.contracts.BackupTaskState
import com.mineg.mobile.contracts.MediaResourceType
import com.mineg.mobile.contracts.MediaSourcePort
import com.mineg.mobile.contracts.Stage03Client
import com.mineg.mobile.contracts.TransportPort
import com.mineg.mobile.core.CoreClient
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage03ContractTest {
  private val manifest by lazy { resource("stage03-v1.json") }

  @Test
  fun baselineRegistersEncryptionUploadAndRecoverySurface() {
    listOf(
      "backupSingleMedia", "getSingleMediaBackup", "uploadPart", "PREPARING", "SERVER_VERIFYING",
      "COMPLETED", "THUMBNAIL", "VIDEO_COVER", "CreateSingleMediaBackup", "RecordUploadedPart",
      "SingleMediaBackupChanged", "mineg_core_encrypt_media_resource", "ACCOUNT_PRIVATE_HMAC",
      "NO_BACKUP_FILES_UNTIL_SERVER_COMPLETED", "backup.overview.singleMediaStatus",
    ).forEach { assertContains(manifest, "\"$it\"") }
    assertContains(manifest, "\"status\": \"BASELINED\"")
  }

  @Test
  fun publicInterfacesExposeOnlyPlatformNeutralBackupNames() {
    assertTrue(Stage03Client::class.java.methods.map { it.name }.containsAll(setOf("backupSingleMedia", "getSingleMediaBackup")))
    assertContains(TransportPort::class.java.methods.map { it.name }, "uploadPart")
    assertTrue(MediaSourcePort::class.java.methods.map { it.name }.containsAll(setOf("openMediaResource", "createDerivedMediaResources")))
    listOf(Stage03Client::class.java, TransportPort::class.java, MediaSourcePort::class.java).forEach { type ->
      type.methods.flatMap { it.parameterTypes.asList() + it.returnType }.forEach {
        assertFalse(it.name.startsWith("android."), "${type.simpleName} exposes ${it.name}")
      }
    }
  }

  @Test
  fun coreAndEnumsMatchTheBaseline() {
    val coreMethods = CoreClient::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }.map { it.name }.toSet()
    assertTrue(coreMethods.containsAll(setOf("createMediaKeyEnvelope", "computeDedupeFingerprint", "encryptMediaResource", "encryptMediaManifest")))
    assertContains(BackupTaskState.entries, BackupTaskState.RETRYABLE_FAILED)
    assertContains(MediaResourceType.entries, MediaResourceType.ORIGINAL)
  }

  @Test
  fun sqliteV4StoresOnlyCiphertextMetadataAndRecoveryTruth() {
    val migration = resource("004_single_media_backup.sql")
    listOf("backup_tasks", "backup_resources", "backup_parts", "server_upload_id", "ciphertext_path").forEach {
      assertContains(migration, it)
    }
    listOf("plaintext_path", "media_key_plaintext", "user_master_key", "password").forEach {
      assertFalse(migration.contains(it, ignoreCase = true), "migration contains forbidden field $it")
    }
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}

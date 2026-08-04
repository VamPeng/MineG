package com.mineg.mobile.platform

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateOriginalDiskStoreTest {
  @Test
  fun `stored original uses the media id as its exact filename`() = withStore { root, store ->
    val bytes = ByteArray(32) { it.toByte() }
    val source = File(root, "source").also { it.writeBytes(bytes) }

    val stored = assertNotNull(store.put(ACCOUNT, MEDIA, source, bytes.size.toLong(), digest(bytes)))

    assertEquals(MEDIA, stored.name)
    assertContentEquals(bytes, assertNotNull(store.get(ACCOUNT, MEDIA, bytes.size.toLong(), digest(bytes))).readBytes())
  }

  @Test
  fun `a corrupt private original is deleted before it can be reused`() = withStore { root, store ->
    val bytes = ByteArray(24) { 3 }
    val source = File(root, "source").also { it.writeBytes(bytes) }
    val stored = assertNotNull(store.put(ACCOUNT, MEDIA, source, bytes.size.toLong(), digest(bytes)))
    stored.writeBytes(ByteArray(24) { 4 })

    assertNull(store.get(ACCOUNT, MEDIA, bytes.size.toLong(), digest(bytes)))
    assertFalse(stored.exists())
  }

  @Test
  fun `private originals are isolated and removable by account`() = withStore { root, store ->
    val bytes = ByteArray(16) { 7 }
    val source = File(root, "source").also { it.writeBytes(bytes) }
    val first = assertNotNull(store.put("account-a", MEDIA, source, bytes.size.toLong(), digest(bytes)))
    val second = assertNotNull(store.put("account-b", MEDIA, source, bytes.size.toLong(), digest(bytes)))

    assertNotEquals(first, second)
    store.clearAccount("account-a")
    assertFalse(first.exists())
    assertNotNull(store.get("account-b", MEDIA, bytes.size.toLong(), digest(bytes)))
  }

  @Test
  fun `removal reports success after deleting the original and its temporary sibling`() = withStore { root, store ->
    val bytes = ByteArray(16) { 7 }
    val source = File(root, "source").also { it.writeBytes(bytes) }
    val stored = assertNotNull(store.put(ACCOUNT, MEDIA, source, bytes.size.toLong(), digest(bytes)))
    val temporary = File(stored.parentFile, "${stored.name}.tmp").also { it.writeBytes(bytes) }

    assertTrue(store.remove(ACCOUNT, MEDIA))
    assertFalse(stored.exists())
    assertFalse(temporary.exists())
  }

  @Test
  fun `removal reports failure when the temporary sibling cannot be deleted`() = withStore { root, store ->
    val bytes = ByteArray(16) { 7 }
    val source = File(root, "source").also { it.writeBytes(bytes) }
    val stored = assertNotNull(store.put(ACCOUNT, MEDIA, source, bytes.size.toLong(), digest(bytes)))
    val temporary = File(stored.parentFile, "${stored.name}.tmp").also { directory ->
      check(directory.mkdirs())
      File(directory, "retained").writeBytes(bytes)
    }

    assertFalse(store.remove(ACCOUNT, MEDIA))
    assertFalse(stored.exists())
    assertTrue(temporary.exists())
  }

  private fun withStore(block: (File, PrivateOriginalDiskStore) -> Unit) {
    val root = Files.createTempDirectory("mineg-private-original-test").toFile()
    try {
      block(root, PrivateOriginalDiskStore(File(root, "originals")))
    } finally {
      root.deleteRecursively()
    }
  }

  private fun digest(bytes: ByteArray): String = Base64.getEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

  private companion object {
    const val ACCOUNT = "account-123"
    const val MEDIA = "11111111-1111-4111-8111-111111111111"
  }
}

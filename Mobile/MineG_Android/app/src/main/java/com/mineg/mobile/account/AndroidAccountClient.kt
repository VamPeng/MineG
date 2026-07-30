package com.mineg.mobile.account

import android.util.Base64
import com.mineg.mobile.contracts.AccountClient
import com.mineg.mobile.contracts.AccountNextStep
import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.AccountSession
import com.mineg.mobile.contracts.AccountStateSnapshot
import com.mineg.mobile.contracts.AlbumCursor
import com.mineg.mobile.contracts.ApiRequest
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.BackupSettings
import com.mineg.mobile.contracts.KeyGrantKind
import com.mineg.mobile.contracts.KeyMaterial
import com.mineg.mobile.contracts.LibraryPermissionState
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.LocalAlbumPage
import com.mineg.mobile.contracts.LocalMedia
import com.mineg.mobile.contracts.LocalMediaCursor
import com.mineg.mobile.contracts.LocalMediaPage
import com.mineg.mobile.contracts.LocalScanState
import com.mineg.mobile.contracts.LocalScanStatus
import com.mineg.mobile.contracts.MediaScanCursor
import com.mineg.mobile.contracts.MediaSourcePort
import com.mineg.mobile.contracts.PendingKeyGrant
import com.mineg.mobile.contracts.Profile
import com.mineg.mobile.contracts.SecureStorePort
import com.mineg.mobile.contracts.Stage02Client
import com.mineg.mobile.contracts.TransportPort
import com.mineg.mobile.contracts.UploadPartRequest
import com.mineg.mobile.contracts.FilePort
import com.mineg.mobile.contracts.BackupPart
import com.mineg.mobile.contracts.BackupResource
import com.mineg.mobile.contracts.BackupTaskState
import com.mineg.mobile.contracts.MediaResourceType
import com.mineg.mobile.contracts.OwnerMediaSummary
import com.mineg.mobile.contracts.OwnerMediaClient
import com.mineg.mobile.contracts.SingleMediaBackup
import com.mineg.mobile.contracts.Stage03Client
import com.mineg.mobile.core.CoreClient
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.json.JSONArray

class AndroidAccountClient(
  private val core: CoreClient,
  private val secureStore: SecureStorePort,
  private val transport: TransportPort,
  private val mediaSource: MediaSourcePort,
  private val files: FilePort,
) : AccountClient, Stage02Client, Stage03Client, OwnerMediaClient {
  private val operationIds = AtomicLong(10_000)
  private var session: AccountSession? = null
  private var accountState: AccountStateSnapshot? = null

  fun dropTransitionalSession() {
    session = null
    accountState = null
  }

  fun deviceInstallationId(): String = installationID()

  override suspend fun signUp(phone: String, password: ByteArray, idempotencyKey: String): AccountSession {
    val normalized = AccountValidation.normalizePhone(phone)
      ?: throw localProblem("PHONE_INVALID", "account.phone.invalid")
    val material = core.createUserKeyBundle(password)
    try {
      val body = JSONObject()
        .put("phone", normalized)
        .put("password", password.toString(Charsets.UTF_8))
        .put("public_key", encodeBase64(material.publicKey))
        .put("encrypted_key_bundle", encodeBase64(material.encryptedKeyBundle))
        .put("kdf_parameters", JSONObject(material.kdfParametersJson))
        .put("bundle_version", 1)
        .put("device_installation_id", installationID())
        .put("platform", "ANDROID")
        .toString()
      val created = sendSessionRequest(
        ApiRequest(
          method = "POST",
          path = "/api/v1/auth/register",
          body = body.toByteArray(),
          headers = mapOf("Idempotency-Key" to idempotencyKey),
        ),
      )
      saveSession(created, AccountValidation.maskedPhone(normalized))
      runCatching { completeFamilyKeyGrant(password) }
      return created
    } finally {
      material.publicKey.fill(0)
      material.encryptedKeyBundle.fill(0)
    }
  }

  override suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountSession {
    val normalized = AccountValidation.normalizePhone(phone)
      ?: throw localProblem("PHONE_INVALID", "account.phone.invalid")
    if (!agreementAccepted) throw localProblem("AGREEMENT_REQUIRED", "account.agreement.required")
    val body = JSONObject()
      .put("phone", normalized)
      .put("password", password)
      .put("device_installation_id", installationID())
      .put("platform", "ANDROID")
      .put("agreement_accepted", true)
      .put("terms_version", "1.0")
      .put("privacy_version", "1.0")
      .toString()
    val signedIn = sendSessionRequest(ApiRequest("POST", "/api/v1/auth/login", body.toByteArray()))
    saveSession(signedIn, AccountValidation.maskedPhone(normalized))
    val passwordBytes = password.toByteArray()
    try {
      runCatching { completeFamilyKeyGrant(passwordBytes) }
    } finally {
      passwordBytes.fill(0)
    }
    return signedIn
  }

  override suspend fun signOut() {
    val refreshToken = session?.refreshToken ?: readSecret(REFRESH_TOKEN)
    try {
      if (!refreshToken.isNullOrBlank()) {
        val body = JSONObject().put("refresh_token", refreshToken).toString().toByteArray()
        send(ApiRequest("POST", "/api/v1/auth/logout", body))
      }
    } finally {
      clearLocalSession()
    }
  }

  override suspend fun restoreSession(): AccountSession? {
    val refreshToken = readSecret(REFRESH_TOKEN) ?: return null
    val accessToken = readSecret(ACCESS_TOKEN)
    val accessExpiry = readSecret(ACCESS_EXPIRY)
    val refreshExpiry = readSecret(REFRESH_EXPIRY)
    accountState = try {
      readAccountState()
    } catch (_: Throwable) {
      clearLocalSession()
      return null
    }
    val accessIsValid = try {
      accessExpiry != null && Instant.parse(accessExpiry).isAfter(Instant.now().plusSeconds(30))
    } catch (_: Throwable) {
      clearLocalSession()
      return null
    }
    val restoredState = accountState
    if (accessToken != null && accessIsValid && accessExpiry != null && refreshExpiry != null && restoredState != null) {
      return AccountSession(
        userId = restoredState.userId,
        accessToken = accessToken,
        accessExpiresAt = accessExpiry,
        refreshToken = refreshToken,
        refreshExpiresAt = refreshExpiry,
        approvalStatus = restoredState.approvalStatus,
        nextStep = if (restoredState.approvalStatus == ApprovalStatus.APPROVED) {
          AccountNextStep.APP_HOME
        } else {
          AccountNextStep.REVIEW_PENDING
        },
      ).also {
        session = it
        restoreKeyAccess(it.approvalStatus == ApprovalStatus.APPROVED)
      }
    }
    return try {
      refresh(refreshToken).also { restoreKeyAccess(it.approvalStatus == ApprovalStatus.APPROVED) }
    } catch (problem: AccountProblem) {
      if (problem.code in SESSION_FAILURES) {
        clearLocalSession()
        null
      } else {
        throw problem
      }
    }
  }

  override suspend fun refreshReviewStatus(): ApprovalStatus {
    runCatching { completeFamilyKeyGrant(null) }
    val active = requireSession()
    val response = sendAuthorized(ApiRequest("GET", "/api/v1/auth/approval-status"), active)
    val payload = JSONObject(response.body.toString(Charsets.UTF_8))
    val status = ApprovalStatus.valueOf(payload.getString("status"))
    val updated = active.copy(
      approvalStatus = status,
      nextStep = if (status == ApprovalStatus.APPROVED) AccountNextStep.APP_HOME else AccountNextStep.REVIEW_PENDING,
    )
    session = updated
    persistAccountState(updated.userId, accountState?.maskedPhone ?: "***********", status)
    if (status == ApprovalStatus.APPROVED) completeFamilyKeyGrant(null)
    return status
  }

  override suspend fun getProfile(): Profile {
    val response = sendAuthorized(ApiRequest("GET", "/api/v1/me"), requireSession())
    val payload = JSONObject(response.body.toString(Charsets.UTF_8))
    return Profile(
      id = payload.getString("id"),
      nickname = payload.getString("nickname"),
      maskedPhone = payload.getString("masked_phone"),
      avatarUrl = payload.optString("avatar_url").ifBlank { null },
    )
  }

  override suspend fun updateProfile(nickname: String): Profile {
    val body = JSONObject().put("nickname", nickname).toString().toByteArray()
    val response = sendAuthorized(ApiRequest("PATCH", "/api/v1/me/profile", body), requireSession())
    val payload = JSONObject(response.body.toString(Charsets.UTF_8))
    return Profile(
      id = payload.getString("id"),
      nickname = payload.getString("nickname"),
      maskedPhone = payload.getString("masked_phone"),
      avatarUrl = payload.optString("avatar_url").ifBlank { null },
    )
  }

  override suspend fun updateAvatar(
    displayBytes: ByteArray,
    sourceSize: Long,
    width: Int,
    height: Int,
    contentType: String,
  ): Profile {
    require(displayBytes.isNotEmpty() && sourceSize in 1..10L * 1024 * 1024 && width in 1..1024 && height == width)
    val digest = MessageDigest.getInstance("SHA-256").digest(displayBytes)
    try {
      val body = JSONObject()
        .put("content_type", contentType)
        .put("source_size", sourceSize)
        .put("display_size", displayBytes.size)
        .put("width", width)
        .put("height", height)
        .put("content_sha256", encodeBase64(digest))
        .toString()
        .toByteArray()
      val response = sendAuthorized(
        ApiRequest(
          "POST",
          "/api/v1/me/avatar/uploads",
          body,
          mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        ),
        requireSession(),
      )
      val payload = JSONObject(response.body.toString(Charsets.UTF_8))
      val uploadId = payload.getString("upload_id")
      val grant = payload.getJSONObject("grant")
      val connection = URL(grant.getString("url")).openConnection() as HttpURLConnection
      try {
        connection.requestMethod = grant.getString("method")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(displayBytes.size)
        val headers = grant.getJSONObject("headers")
        headers.keys().forEach { name -> connection.setRequestProperty(name, headers.getString(name)) }
        connection.outputStream.use { it.write(displayBytes) }
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("avatar object upload failed with status $status")
      } finally {
        connection.disconnect()
      }
      val completed = sendAuthorized(
        ApiRequest("POST", "/api/v1/me/avatar/uploads/$uploadId/complete", "{}".toByteArray()),
        requireSession(),
      )
      val profile = JSONObject(completed.body.toString(Charsets.UTF_8))
      return Profile(
        id = profile.getString("id"),
        nickname = profile.getString("nickname"),
        maskedPhone = profile.getString("masked_phone"),
        avatarUrl = profile.optString("avatar_url").ifBlank { null },
      )
    } finally {
      digest.fill(0)
    }
  }

  override suspend fun getKeyBundle(): KeyMaterial {
    val response = sendAuthorized(ApiRequest("GET", "/api/v1/me/key-bundle"), requireSession())
    val payload = JSONObject(response.body.toString(Charsets.UTF_8))
    return KeyMaterial(
      publicKey = decodeBase64(payload.getString("public_key")),
      encryptedKeyBundle = decodeBase64(payload.getString("encrypted_key_bundle")),
      kdfParameters = payload.getJSONObject("kdf_parameters").toString(),
      bundleVersion = payload.getInt("bundle_version"),
      familyEnvelope = payload.optString("family_envelope").takeIf { it.isNotBlank() }?.let(::decodeBase64),
    )
  }

  override suspend fun completeFamilyKeyGrant(password: ByteArray?): Boolean {
    val material = getKeyBundle()
    try {
      if (password != null) {
        val wrapKey = deviceWrapKey()
        try {
          val unlockBlob = core.unlockUserKeyBundle(password, material.publicKey, material.encryptedKeyBundle, wrapKey)
          secureStore.writeSecret(KEY_PUBLIC, material.publicKey)
          secureStore.writeSecret(KEY_UNLOCK_BLOB, unlockBlob)
          unlockBlob.fill(0)
        } finally {
          wrapKey.fill(0)
        }
      } else {
        restoreKeyAccess(false)
      }
      material.familyEnvelope?.let(core::unlockFamilyKeyEnvelope)
      val active = requireSession()
      val response = sendAuthorized(ApiRequest("GET", "/api/v1/key-grants/pending?limit=20"), active)
      val items = JSONObject(response.body.toString(Charsets.UTF_8)).getJSONArray("items")
      var completed = false
      for (index in 0 until items.length()) {
        val grant = parsePendingGrant(items.getJSONObject(index))
        val envelope = core.createFamilyKeyEnvelope(
          grant.recipientPublicKey,
          bootstrapIfNeeded = grant.kind == KeyGrantKind.FAMILY_BOOTSTRAP,
        )
        try {
          val body = JSONObject()
            .put("recipient_public_key", encodeBase64(grant.recipientPublicKey))
            .put("encrypted_envelope", encodeBase64(envelope))
            .put("algorithm", "X25519_SEALED_BOX")
            .put("envelope_version", 1)
            .toString()
            .toByteArray()
          sendAuthorized(ApiRequest("POST", "/api/v1/key-grants/${grant.id}/complete", body), requireSession())
          completed = true
        } finally {
          envelope.fill(0)
          grant.recipientPublicKey.fill(0)
        }
      }
      return completed
    } finally {
      material.publicKey.fill(0)
      material.encryptedKeyBundle.fill(0)
      material.familyEnvelope?.fill(0)
    }
  }

  override fun getBackupSettings(userId: String, deviceInstallationId: String): BackupSettings {
    val payload = JSONObject(
      core.query(
        JSONObject()
          .put("version", 1)
          .put("type", "GetBackupSettings")
          .put("userId", userId)
          .put("deviceInstallationId", deviceInstallationId)
          .toString(),
      ),
    ).getJSONObject("settings")
    return BackupSettings(
      autoBackupEnabled = payload.getBoolean("autoBackupEnabled"),
      allowCellularBackup = payload.getBoolean("allowCellularBackup"),
      updatedAt = payload.optString("updatedAt").ifBlank { null },
    )
  }

  override fun updateBackupSettings(userId: String, deviceInstallationId: String, settings: BackupSettings) {
    val updatedAt = Instant.now().toString()
    core.execute(
      operationIds.getAndIncrement(),
      JSONObject()
        .put("version", 1)
        .put("type", "UpdateBackupSettings")
        .put("userId", userId)
        .put("deviceInstallationId", deviceInstallationId)
        .put("autoBackupEnabled", settings.autoBackupEnabled)
        .put("allowCellularBackup", settings.allowCellularBackup)
        .put("updatedAt", updatedAt)
        .toString(),
    )
  }

  override fun scanLocalMedia(userId: String): LocalScanState {
    if (mediaSource.getPermissionSnapshot().library != LibraryPermissionState.FULL) {
      core.execute(
        operationIds.getAndIncrement(),
        JSONObject()
          .put("version", 1)
          .put("type", "MarkLocalScanBlocked")
          .put("userId", userId)
          .put("updatedAt", Instant.now().toString())
          .toString(),
      )
      return readScanState(userId)
    }
    val previousScan = readScanState(userId)
    val resume = previousScan.status == LocalScanStatus.SCANNING && previousScan.scanGeneration.isNotBlank()
    val scanGeneration = if (resume) previousScan.scanGeneration else UUID.randomUUID().toString()
    val albums = mediaSource.listAlbums()
    var cursor: MediaScanCursor? = if (resume && previousScan.cursorAssetRef.isNotBlank()) {
      MediaScanCursor(previousScan.cursorModifiedVersion, previousScan.cursorAssetRef)
    } else {
      null
    }
    do {
      val page = mediaSource.listMedia(cursor, 500)
      val media = JSONArray()
      val relations = JSONArray()
      page.items.forEach { item ->
        media.put(
          JSONObject()
            .put("platformAssetRef", item.platformAssetRef)
            .put("mediaType", item.mediaType.name)
            .put("mimeType", item.mimeType)
            .put("width", item.width)
            .put("height", item.height)
            .put("durationMs", item.durationMs ?: JSONObject.NULL)
            .put("capturedAt", item.capturedAt)
            .put("modifiedAt", item.modifiedAt)
            .put("modifiedVersion", item.modifiedVersion)
            .put("contentVersion", item.contentVersion)
            .put("availability", item.availability.name)
            .put("thumbnailUri", item.thumbnailUri ?: JSONObject.NULL),
        )
        relations.put(
          JSONObject()
            .put("platformAssetRef", item.platformAssetRef)
            .put("platformAlbumRef", item.platformAlbumRef),
        )
      }
      val next = page.nextCursor
      core.execute(
        operationIds.getAndIncrement(),
        JSONObject()
          .put("version", 1)
          .put("type", "ApplyLocalMediaBatch")
          .put("userId", userId)
          .put("scanGeneration", scanGeneration)
          .put("albums", JSONArray().also { values -> albums.forEach { values.put(JSONObject().put("platformAlbumRef", it.platformAlbumRef).put("name", it.name)) } })
          .put("media", media)
          .put("relations", relations)
          .put("cursorModifiedVersion", next?.modifiedVersion ?: page.items.lastOrNull()?.modifiedVersion ?: cursor?.modifiedVersion ?: 0)
          .put("cursorAssetRef", next?.platformAssetRef ?: page.items.lastOrNull()?.platformAssetRef ?: cursor?.platformAssetRef.orEmpty())
          .put("complete", next == null)
          .put("updatedAt", Instant.now().toString())
          .toString(),
      )
      cursor = next
    } while (cursor != null)
    return readScanState(userId)
  }

  override fun listLocalAlbums(userId: String, cursor: AlbumCursor?, limit: Int): LocalAlbumPage {
    val payload = JSONObject(
      core.query(
        JSONObject()
          .put("version", 1)
          .put("type", "ListLocalAlbums")
          .put("userId", userId)
          .put("cursorName", cursor?.name.orEmpty())
          .put("cursorAlbumRef", cursor?.platformAlbumRef.orEmpty())
          .put("limit", limit.coerceIn(1, 100))
          .toString(),
      ),
    )
    val values = payload.getJSONArray("items")
    val items = (0 until values.length()).map { index ->
      values.getJSONObject(index).let {
        LocalAlbum(it.getString("platformAlbumRef"), it.getString("name"), it.getLong("mediaCount"), it.optString("coverThumbnailUri").ifBlank { null })
      }
    }
    val next = payload.optJSONObject("nextCursor")?.let { AlbumCursor(it.getString("name"), it.getString("platformAlbumRef")) }
    return LocalAlbumPage(items, next)
  }

  override fun listLocalMedia(userId: String, albumRef: String?, cursor: LocalMediaCursor?, limit: Int): LocalMediaPage {
    val payload = JSONObject(
      core.query(
        JSONObject()
          .put("version", 1)
          .put("type", "ListLocalMedia")
          .put("userId", userId)
          .put("platformAlbumRef", albumRef.orEmpty())
          .put("cursorCapturedAt", cursor?.capturedAt.orEmpty())
          .put("cursorAssetRef", cursor?.platformAssetRef.orEmpty())
          .put("limit", limit.coerceIn(1, 500))
          .toString(),
      ),
    )
    val values = payload.getJSONArray("items")
    val items = (0 until values.length()).map { index ->
      values.getJSONObject(index).let {
        LocalMedia(
          platformAssetRef = it.getString("platformAssetRef"),
          mediaType = com.mineg.mobile.contracts.LocalMediaType.valueOf(it.getString("mediaType")),
          mimeType = it.getString("mimeType"),
          width = it.getInt("width"), height = it.getInt("height"),
          durationMs = if (it.isNull("durationMs")) null else it.getLong("durationMs"),
          capturedAt = it.getString("capturedAt"), modifiedAt = it.getString("modifiedAt"),
          contentVersion = it.getString("contentVersion"),
          availability = com.mineg.mobile.contracts.LocalMediaAvailability.valueOf(it.getString("availability")),
          thumbnailUri = it.optString("thumbnailUri").ifBlank { null },
        )
      }
    }
    val next = payload.optJSONObject("nextCursor")?.let { LocalMediaCursor(it.getString("capturedAt"), it.getString("platformAssetRef")) }
    return LocalMediaPage(items, next)
  }

  override suspend fun backupSingleMedia(userId: String, media: LocalMedia): SingleMediaBackup {
    if (mediaSource.getPermissionSnapshot().library != LibraryPermissionState.FULL) {
      throw localProblem("PERMISSION_NOT_FULL", "backup.permission.full.required")
    }
    require(media.availability == com.mineg.mobile.contracts.LocalMediaAvailability.AVAILABLE)
    val taskId = UUID.nameUUIDFromBytes("$userId|${media.platformAssetRef}|${media.contentVersion}".toByteArray()).toString()
    val persisted = readSingleMediaBackup(taskId)
    persisted?.let(::backupSummary)?.takeIf { it.state == BackupTaskState.COMPLETED }?.let { return it }
    core.execute(
      operationIds.getAndIncrement(),
      JSONObject().put("version", 1).put("type", "CreateSingleMediaBackup")
        .put("taskId", taskId).put("userId", userId).put("platformAssetRef", media.platformAssetRef)
        .put("contentVersion", media.contentVersion).put("mediaType", media.mediaType.name)
        .put("createdAt", Instant.now().toString()).toString(),
    )
    val recovered = persisted?.optJSONObject("recovery")?.takeIf {
      !it.isNull("encryptedMediaKey") && it.optJSONArray("resources")?.length()?.let { count -> count > 0 } == true
    }
    val material = recovered?.let(::parseRecoveredMaterial) ?: prepareBackupMaterial(taskId, media)
    val ciphertextPaths = material.resources.mapTo(mutableListOf()) { it.ciphertextPath }
    val encryptedMediaKey = material.encryptedMediaKey
    var encryptedManifest: ByteArray? = material.encryptedManifest
    var fingerprint: ByteArray? = material.fingerprint
    try {
      val resources = material.resources
      val manifestDigest = material.manifestDigest
      try {
        if (recovered == null) {
          core.execute(
            operationIds.getAndIncrement(),
            preparedMediaCommand(taskId, checkNotNull(fingerprint), encryptedMediaKey, checkNotNull(encryptedManifest), manifestDigest, resources),
          )
        }
        val created = JSONObject(
          sendAuthorized(
            ApiRequest(
              "POST", "/api/v1/uploads",
              JSONObject().put("client_media_id", taskId)
                .put("dedupe_fingerprint", encodeBase64(checkNotNull(fingerprint)))
                .put("content_revision", contentRevision(media.contentVersion))
                .put("media_type", media.mediaType.name).put("captured_at", media.capturedAt)
                .put("manifest_digest", encodeBase64(manifestDigest))
                .put("encrypted_manifest", encodeBase64(checkNotNull(encryptedManifest)))
                .put("encrypted_media_key", encodeBase64(encryptedMediaKey))
                .put("resources", JSONArray(resources.map(::resourceRequest))).toString().toByteArray(),
              mapOf("Idempotency-Key" to taskId),
            ),
            requireSession(),
          ).body.toString(Charsets.UTF_8),
        )
        val uploadId = created.getString("id")
        val serverState = created.getString("state")
        if (serverState == "COMPLETED") {
          core.execute(
            operationIds.getAndIncrement(),
            JSONObject().put("version", 1).put("type", "CompleteDeduplicatedSingleMediaBackup")
              .put("taskId", taskId).put("serverUploadId", uploadId)
              .put("serverMediaId", created.getString("media_id"))
              .put("updatedAt", Instant.now().toString()).toString(),
          )
          ciphertextPaths.forEach(files::deleteTempFile)
          return checkNotNull(getSingleMediaBackup(taskId))
        }
        core.execute(
          operationIds.getAndIncrement(),
          JSONObject().put("version", 1).put("type", "RecordUploadSession")
            .put("taskId", taskId).put("uploadId", uploadId)
            .put("updatedAt", Instant.now().toString()).toString(),
        )
        if (serverState != "VERIFYING") {
          val grants = created.getJSONObject("grant").getJSONArray("resources")
            .let { values -> (0 until values.length()).map { values.getJSONObject(it) } }
          resources.forEach { resource ->
            val resourceGrant = grants.single { it.getString("resource_id") == resource.resourceId }
            val partGrants = resourceGrant.getJSONArray("parts")
            resource.parts.forEach { part ->
              if (part.etag != null) return@forEach
              val granted = (0 until partGrants.length()).map { partGrants.getJSONObject(it) }
                .single { it.getInt("part_number") == part.partNumber }.getJSONObject("grant")
              val headerObject = granted.getJSONObject("headers")
              val uploaded = transport.uploadPart(
                UploadPartRequest(
                  granted.getString("url"), granted.getString("method"),
                  headerObject.keys().asSequence().associateWith { headerObject.getString(it) },
                  resource.ciphertextPath, part.offset, part.ciphertextSize,
                ),
              )
              sendAuthorized(
                ApiRequest(
                  "POST", "/api/v1/uploads/$uploadId/parts",
                  JSONObject().put("resource_id", resource.resourceId).put("part_number", part.partNumber)
                    .put("ciphertext_size", part.ciphertextSize)
                    .put("ciphertext_sha256", encodeBase64(part.ciphertextSHA256))
                    .put("etag", uploaded.etag).toString().toByteArray(),
                  mapOf("Idempotency-Key" to "$taskId:${resource.resourceId}:${part.partNumber}"),
                ),
                requireSession(),
              )
              core.execute(
                operationIds.getAndIncrement(),
                JSONObject().put("version", 1).put("type", "RecordUploadedPart")
                  .put("taskId", taskId).put("resourceId", resource.resourceId)
                  .put("partNumber", part.partNumber).put("etag", uploaded.etag)
                  .put("updatedAt", Instant.now().toString()).toString(),
              )
            }
          }
        }
        core.execute(
          operationIds.getAndIncrement(),
          JSONObject().put("version", 1).put("type", "MarkServerVerifying")
            .put("taskId", taskId).put("updatedAt", Instant.now().toString()).toString(),
        )
        val completed = JSONObject(
          sendAuthorized(
            ApiRequest(
              "POST", "/api/v1/uploads/$uploadId/complete",
              JSONObject().put("manifest_digest", encodeBase64(manifestDigest)).toString().toByteArray(),
              mapOf("Idempotency-Key" to "$taskId:complete"),
            ),
            requireSession(),
          ).body.toString(Charsets.UTF_8),
        )
        core.execute(
          operationIds.getAndIncrement(),
          JSONObject().put("version", 1).put("type", "CompleteSingleMediaBackup")
            .put("taskId", taskId).put("serverMediaId", completed.getString("media_id"))
            .put("updatedAt", Instant.now().toString()).toString(),
        )
        ciphertextPaths.forEach(files::deleteTempFile)
        return checkNotNull(getSingleMediaBackup(taskId))
      } finally {
        manifestDigest.fill(0)
      }
    } catch (problem: AccountProblem) {
      markBackupFailed(taskId, problem.code, problem.retryable)
      throw problem
    } catch (error: Throwable) {
      markBackupFailed(taskId, "BACKUP_SINGLE_MEDIA_FAILED", true)
      throw error
    } finally {
      encryptedMediaKey.fill(0)
      encryptedManifest?.fill(0)
      fingerprint?.fill(0)
    }
  }

  override fun getSingleMediaBackup(taskId: String): SingleMediaBackup? {
    return readSingleMediaBackup(taskId)?.let(::backupSummary)
  }

  override suspend fun listOwnerMedia(limit: Int): List<OwnerMediaSummary> {
    val pageSize = limit.coerceIn(1, 100)
    val response = sendAuthorized(
      ApiRequest("GET", "/api/v1/media?limit=$pageSize"),
      requireSession(),
    )
    val items = JSONObject(response.body.toString(Charsets.UTF_8)).getJSONArray("items")
    return buildList(items.length()) {
      repeat(items.length()) { index ->
        val item = items.getJSONObject(index)
        add(
          OwnerMediaSummary(
            id = item.getString("id"),
            mediaType = item.getString("media_type"),
            contentRevision = item.getInt("content_revision"),
            capturedAt = item.getString("captured_at"),
            createdAt = item.getString("created_at"),
          ),
        )
      }
    }
  }

  private fun readSingleMediaBackup(taskId: String): JSONObject? {
    val payload = JSONObject(core.query(
      JSONObject().put("version", 1).put("type", "GetSingleMediaBackup").put("taskId", taskId).toString(),
    ))
    return if (payload.isNull("task")) null else payload.getJSONObject("task")
  }

  private fun backupSummary(task: JSONObject) = SingleMediaBackup(
      taskId = task.getString("taskId"), state = BackupTaskState.valueOf(task.getString("state")),
      serverUploadId = task.optString("serverUploadId").ifBlank { null },
      serverMediaId = task.optString("serverMediaId").ifBlank { null },
      uploadedParts = task.getInt("uploadedParts"), partCount = task.getInt("partCount"),
      errorCode = task.optString("errorCode").ifBlank { null },
    )

  private data class BackupMaterial(
    val fingerprint: ByteArray,
    val encryptedMediaKey: ByteArray,
    val encryptedManifest: ByteArray,
    val manifestDigest: ByteArray,
    val resources: List<BackupResource>,
  )

  private fun prepareBackupMaterial(taskId: String, media: LocalMedia): BackupMaterial {
    val paths = mutableListOf<String>()
    val encryptedMediaKey = core.createMediaKeyEnvelope(taskId)
    var fingerprint: ByteArray? = null
    try {
      val resources = mutableListOf<BackupResource>()
      val originalType = MediaResourceType.ORIGINAL
      val originalResourceId = UUID.nameUUIDFromBytes("$taskId|${originalType.name}".toByteArray()).toString()
      val originalPath = files.createEncryptedTempFile("backup-$taskId-original")
      paths += originalPath
      val opened = mediaSource.openMediaResource(media.platformAssetRef)
        ?: throw localProblem("MEDIA_RESOURCE_UNAVAILABLE", "backup.media.resource.unavailable", retryable = true)
      resources += opened.use {
        fingerprint = core.computeDedupeFingerprint(it.descriptor, media.mediaType.name)
        parseBackupResource(
          originalResourceId, originalType, originalPath,
          core.encryptMediaResource(
            it.descriptor, originalPath, taskId, originalResourceId, originalType.name, encryptedMediaKey,
          ),
        )
      }
      mediaSource.createDerivedMediaResources(media.platformAssetRef, media.mediaType).forEach { derived ->
        derived.use {
          val resourceId = UUID.nameUUIDFromBytes("$taskId|${it.resourceType.name}".toByteArray()).toString()
          val path = files.createEncryptedTempFile("backup-$taskId-${it.resourceType.name.lowercase()}")
          paths += path
          resources += parseBackupResource(
            resourceId, it.resourceType, path,
            core.encryptMediaResource(
              it.opened.descriptor, path, taskId, resourceId, it.resourceType.name, encryptedMediaKey,
            ),
          )
        }
      }
      val manifest = JSONObject().put("formatVersion", 1).put("mediaId", taskId)
        .put("mediaType", media.mediaType.name)
        .put("resources", JSONArray(resources.map { JSONObject(it.manifestJson) })).toString().toByteArray()
      val encryptedManifest = try {
        core.encryptMediaManifest(taskId, manifest, encryptedMediaKey)
      } finally {
        manifest.fill(0)
      }
      return BackupMaterial(
        checkNotNull(fingerprint), encryptedMediaKey, encryptedManifest,
        MessageDigest.getInstance("SHA-256").digest(encryptedManifest), resources,
      )
    } catch (error: Throwable) {
      encryptedMediaKey.fill(0)
      fingerprint?.fill(0)
      paths.forEach { runCatching { files.deleteTempFile(it) } }
      throw error
    }
  }

  private fun parseRecoveredMaterial(recovery: JSONObject): BackupMaterial {
    val resources = recovery.getJSONArray("resources").let { values ->
      (0 until values.length()).map { index ->
        val resource = values.getJSONObject(index)
        val parts = resource.getJSONArray("parts").let { partValues ->
          (0 until partValues.length()).map { partIndex ->
            partValues.getJSONObject(partIndex).let { part ->
              BackupPart(
                part.getInt("partNumber"), part.getLong("offset"), part.getLong("ciphertextSize"),
                decodeBase64(part.getString("ciphertextSha256")),
                part.optString("etag").ifBlank { null },
              )
            }
          }
        }
        BackupResource(
          resource.getString("resourceId"), MediaResourceType.valueOf(resource.getString("resourceType")),
          resource.getString("ciphertextPath"), resource.getLong("ciphertextSize"),
          decodeBase64(resource.getString("ciphertextSha256")), resource.getJSONObject("manifest").toString(), parts,
        )
      }
    }
    return BackupMaterial(
      decodeBase64(recovery.getString("dedupeFingerprint")),
      decodeBase64(recovery.getString("encryptedMediaKey")),
      decodeBase64(recovery.getString("encryptedManifest")),
      decodeBase64(recovery.getString("manifestDigest")),
      resources,
    )
  }

  private fun parseBackupResource(
    resourceId: String,
    resourceType: MediaResourceType,
    path: String,
    manifest: String,
  ): BackupResource {
    val value = JSONObject(manifest)
    val parts = value.getJSONArray("parts").let { items ->
      (0 until items.length()).map { index ->
        items.getJSONObject(index).let { part ->
          BackupPart(
            part.getInt("partNumber"), part.getLong("offset"), part.getLong("ciphertextSize"),
            decodeHex(part.getString("ciphertextSha256")),
          )
        }
      }
    }
    return BackupResource(
      resourceId, resourceType, path, value.getLong("ciphertextSize"),
      decodeHex(value.getString("ciphertextSha256")), manifest, parts,
    )
  }

  private fun preparedMediaCommand(
    taskId: String,
    fingerprint: ByteArray,
    encryptedMediaKey: ByteArray,
    encryptedManifest: ByteArray,
    manifestDigest: ByteArray,
    resources: List<BackupResource>,
  ): String = JSONObject().put("version", 1).put("type", "RecordPreparedMedia").put("taskId", taskId)
    .put("dedupeFingerprint", encodeBase64(fingerprint)).put("encryptedMediaKey", encodeBase64(encryptedMediaKey))
    .put("encryptedManifest", encodeBase64(encryptedManifest)).put("manifestDigest", encodeBase64(manifestDigest))
    .put(
      "resources",
      JSONArray(
        resources.map { resource ->
          JSONObject().put("resourceId", resource.resourceId).put("resourceType", resource.resourceType.name)
            .put("ciphertextPath", resource.ciphertextPath).put("ciphertextSize", resource.ciphertextSize)
            .put("ciphertextSha256", encodeBase64(resource.ciphertextSHA256))
            .put("manifest", JSONObject(resource.manifestJson))
            .put("parts", JSONArray(resource.parts.map(::partJson)))
        },
      ),
    ).put("updatedAt", Instant.now().toString()).toString()

  private fun resourceRequest(resource: BackupResource) = JSONObject()
    .put("resource_id", resource.resourceId).put("resource_type", resource.resourceType.name)
    .put("ciphertext_size", resource.ciphertextSize)
    .put("ciphertext_sha256", encodeBase64(resource.ciphertextSHA256))
    .put(
      "parts",
      JSONArray(
        resource.parts.map {
          JSONObject().put("part_number", it.partNumber).put("ciphertext_size", it.ciphertextSize)
            .put("ciphertext_sha256", encodeBase64(it.ciphertextSHA256))
        },
      ),
    )

  private fun partJson(part: BackupPart) = JSONObject().put("partNumber", part.partNumber)
    .put("offset", part.offset).put("ciphertextSize", part.ciphertextSize)
    .put("ciphertextSha256", encodeBase64(part.ciphertextSHA256))

  private fun contentRevision(value: String): Int =
    value.substringBefore(':').toLongOrNull()?.coerceIn(1, Int.MAX_VALUE.toLong())?.toInt() ?: 1

  private fun decodeHex(value: String): ByteArray {
    require(value.length == 64)
    return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
  }

  private fun markBackupFailed(taskId: String, code: String, retryable: Boolean) {
    runCatching {
      core.execute(
        operationIds.getAndIncrement(),
        JSONObject().put("version", 1).put("type", "MarkSingleMediaBackupFailed")
          .put("taskId", taskId).put("errorCode", code).put("retryable", retryable)
          .put("updatedAt", Instant.now().toString()).toString(),
      )
    }
  }

  private suspend fun requireSession(): AccountSession {
    val current = session ?: restoreSession() ?: throw localProblem("SESSION_INVALID", "account.session.invalid")
    if (Instant.parse(current.accessExpiresAt).isAfter(Instant.now().plusSeconds(30))) return current
    return refresh(current.refreshToken)
  }

  private suspend fun refresh(refreshToken: String): AccountSession {
    val body = JSONObject().put("refresh_token", refreshToken).toString().toByteArray()
    return try {
      sendSessionRequest(ApiRequest("POST", "/api/v1/auth/refresh", body)).also {
        saveSession(it, accountState?.maskedPhone ?: "***********")
      }
    } catch (problem: AccountProblem) {
      if (problem.code in SESSION_FAILURES) clearLocalSession()
      throw problem
    }
  }

  private suspend fun sendAuthorized(request: ApiRequest, active: AccountSession) = try {
    send(request.copy(headers = request.headers + ("Authorization" to "Bearer ${active.accessToken}")))
  } catch (problem: AccountProblem) {
    if (problem.code !in SESSION_FAILURES) throw problem
    val refreshed = refresh(active.refreshToken)
    send(request.copy(headers = request.headers + ("Authorization" to "Bearer ${refreshed.accessToken}")))
  }

  private suspend fun sendSessionRequest(request: ApiRequest): AccountSession {
    val payload = JSONObject(send(request).body.toString(Charsets.UTF_8))
    return AccountSession(
      userId = payload.getString("user_id"),
      accessToken = payload.getString("access_token"),
      accessExpiresAt = payload.getString("access_expires_at"),
      refreshToken = payload.getString("refresh_token"),
      refreshExpiresAt = payload.getString("refresh_expires_at"),
      approvalStatus = ApprovalStatus.valueOf(payload.getString("approval_status")),
      nextStep = AccountNextStep.valueOf(payload.getString("next_step")),
    )
  }

  private suspend fun send(request: ApiRequest) = try {
    val response = transport.sendApiRequest(request)
    if (response.status in 200..299) response else throw parseProblem(response.status, response.requestId, response.body)
  } catch (problem: AccountProblem) {
    throw problem
  } catch (_: java.net.SocketTimeoutException) {
    throw localProblem("NETWORK_UNAVAILABLE", "account.network.unavailable", retryable = true)
  } catch (_: IOException) {
    throw localProblem("NETWORK_UNAVAILABLE", "account.network.unavailable", retryable = true)
  }

  private fun parseProblem(status: Int, fallbackRequestID: String?, body: ByteArray): AccountProblem {
    return runCatching {
      val payload = JSONObject(body.toString(Charsets.UTF_8))
      AccountProblem(
        code = payload.getString("code"),
        messageKey = "account.${payload.getString("code").lowercase()}",
        retryable = payload.optBoolean("retryable", status >= 500),
        requestId = payload.optString("request_id", fallbackRequestID.orEmpty()),
      )
    }.getOrElse {
      localProblem("SERVICE_UNAVAILABLE", "account.service.unavailable", retryable = status >= 500)
    }
  }

  private fun saveSession(value: AccountSession, maskedPhone: String) {
    secureStore.writeSecret(ACCESS_TOKEN, value.accessToken.toByteArray())
    secureStore.writeSecret(REFRESH_TOKEN, value.refreshToken.toByteArray())
    secureStore.writeSecret(ACCESS_EXPIRY, value.accessExpiresAt.toByteArray())
    secureStore.writeSecret(REFRESH_EXPIRY, value.refreshExpiresAt.toByteArray())
    session = value
    persistAccountState(value.userId, maskedPhone, value.approvalStatus)
  }

  private fun persistAccountState(userId: String, maskedPhone: String, status: ApprovalStatus) {
    val updatedAt = Instant.now().toString()
    core.execute(
      operationIds.getAndIncrement(),
      JSONObject()
        .put("version", 1)
        .put("type", "PersistAccountState")
        .put("userId", userId)
        .put("maskedPhone", maskedPhone)
        .put("approvalStatus", status.name)
        .put("updatedAt", updatedAt)
        .toString(),
    )
    accountState = AccountStateSnapshot(userId, maskedPhone, status, updatedAt)
  }

  private fun readAccountState(): AccountStateSnapshot? {
    val payload = JSONObject(core.query("{\"version\":1,\"type\":\"GetAccountState\"}"))
    if (payload.isNull("state")) return null
    val state = payload.getJSONObject("state")
    return AccountStateSnapshot(
      userId = state.getString("userId"),
      maskedPhone = state.getString("maskedPhone"),
      approvalStatus = ApprovalStatus.valueOf(state.getString("approvalStatus")),
      updatedAt = state.getString("updatedAt"),
    )
  }

  private fun clearLocalSession() {
    listOf(ACCESS_TOKEN, REFRESH_TOKEN, ACCESS_EXPIRY, REFRESH_EXPIRY, KEY_PUBLIC, KEY_UNLOCK_BLOB).forEach(secureStore::deleteSecret)
    core.lockKeys()
    session = null
    accountState = null
    runCatching {
      core.execute(operationIds.getAndIncrement(), "{\"version\":1,\"type\":\"ClearAccountState\"}")
    }
  }

  private fun installationID(): String {
    readSecret(INSTALLATION_ID)?.let { return it }
    return UUID.randomUUID().toString().also { secureStore.writeSecret(INSTALLATION_ID, it.toByteArray()) }
  }

  private fun deviceWrapKey(): ByteArray {
    secureStore.readSecret(DEVICE_WRAP_KEY)?.let { existing ->
      if (existing.size == 32) return existing
      existing.fill(0)
      secureStore.deleteSecret(DEVICE_WRAP_KEY)
    }
    return core.randomKey().also { generated -> secureStore.writeSecret(DEVICE_WRAP_KEY, generated) }
  }

  private suspend fun restoreKeyAccess(unlockFamily: Boolean) {
    val publicKey = secureStore.readSecret(KEY_PUBLIC) ?: return
    val unlockBlob = secureStore.readSecret(KEY_UNLOCK_BLOB) ?: run {
      publicKey.fill(0)
      return
    }
    val wrapKey = deviceWrapKey()
    try {
      core.restoreUserKeyBundle(publicKey, wrapKey, unlockBlob)
      if (unlockFamily) {
        val material = getKeyBundle()
        try {
          material.familyEnvelope?.let(core::unlockFamilyKeyEnvelope)
        } finally {
          material.publicKey.fill(0)
          material.encryptedKeyBundle.fill(0)
          material.familyEnvelope?.fill(0)
        }
      }
    } finally {
      publicKey.fill(0)
      unlockBlob.fill(0)
      wrapKey.fill(0)
    }
  }

  private fun parsePendingGrant(payload: JSONObject): PendingKeyGrant = PendingKeyGrant(
    id = payload.getString("id"),
    userId = payload.getString("user_id"),
    familyId = payload.getString("family_id"),
    kind = KeyGrantKind.valueOf(payload.getString("kind")),
    recipientPublicKey = decodeBase64(payload.getString("recipient_public_key")),
    bundleVersion = payload.getInt("bundle_version"),
    createdAt = payload.getString("created_at"),
  )

  private fun readScanState(userId: String): LocalScanState {
    val payload = JSONObject(
      core.query(JSONObject().put("version", 1).put("type", "GetLocalScanState").put("userId", userId).toString()),
    ).getJSONObject("state")
    return LocalScanState(
      cursorModifiedVersion = payload.getLong("cursorModifiedVersion"),
      cursorAssetRef = payload.getString("cursorAssetRef"),
      status = LocalScanStatus.valueOf(payload.getString("status")),
      indexedCount = payload.getLong("indexedCount"),
      scanGeneration = payload.getString("scanGeneration"),
      updatedAt = payload.optString("updatedAt").ifBlank { null },
    )
  }

  private fun readSecret(name: String): String? {
    val bytes = secureStore.readSecret(name) ?: return null
    return try {
      bytes.toString(Charsets.UTF_8)
    } finally {
      bytes.fill(0)
    }
  }

  private fun encodeBase64(value: ByteArray): String =
    Base64.encodeToString(value, Base64.NO_WRAP or Base64.NO_PADDING)

  private fun decodeBase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP or Base64.NO_PADDING)

  private fun localProblem(code: String, key: String, retryable: Boolean = false) =
    AccountProblem(code, key, retryable, "")

  private companion object {
    const val ACCESS_TOKEN = "account.accessToken"
    const val REFRESH_TOKEN = "account.refreshToken"
    const val ACCESS_EXPIRY = "account.accessExpiresAt"
    const val REFRESH_EXPIRY = "account.refreshExpiresAt"
    const val INSTALLATION_ID = "device.installationId"
    const val DEVICE_WRAP_KEY = "keys.deviceWrapKey"
    const val KEY_PUBLIC = "keys.userPublicKey"
    const val KEY_UNLOCK_BLOB = "keys.deviceUnlockBlob"
    val SESSION_FAILURES = setOf("AUTH_REQUIRED", "SESSION_INVALID", "SESSION_EXPIRED", "SESSION_REPLAYED")
  }
}

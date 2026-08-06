/**
 * Application runtime facade and its Android composition root.
 *
 * Presentation depends only on [MineGAppRuntime]; the Android implementation wires domain
 * gateways, native Core, platform ports, caches and background scheduling.
 */
package com.mineg.mobile.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import coil3.imageLoader
import coil3.memory.MemoryCache
import com.mineg.mobile.BuildConfig
import com.mineg.mobile.bridge.account.AccountCoreGateway
import com.mineg.mobile.bridge.backup.BackupCycleResult
import com.mineg.mobile.bridge.backup.BackupOverview
import com.mineg.mobile.bridge.backup.BackupQueueCoreGateway
import com.mineg.mobile.bridge.backup.BackupQueueSummary
import com.mineg.mobile.bridge.backup.BackupSettingsCoreGateway
import com.mineg.mobile.bridge.backup.LocalAlbumBackupProgress
import com.mineg.mobile.bridge.feedback.FeedbackCoreGateway
import com.mineg.mobile.bridge.library.LocalLibraryCoreGateway
import com.mineg.mobile.bridge.media.PrivateMediaCoreGateway
import com.mineg.mobile.bridge.profile.ProfileCoreGateway
import com.mineg.mobile.bridge.shared.SharedMediaCoreGateway
import com.mineg.mobile.bridge.trash.TrashCoreGateway
import com.mineg.mobile.bridge.account.AccountRouteSnapshot
import com.mineg.mobile.bridge.account.ApprovalStatus
import com.mineg.mobile.platform.port.LibraryPermissionState
import com.mineg.mobile.bridge.library.model.LocalAlbum
import com.mineg.mobile.bridge.library.model.BackupSettings
import com.mineg.mobile.bridge.library.model.LocalLibrarySummary
import com.mineg.mobile.bridge.library.model.LocalMedia
import com.mineg.mobile.bridge.library.model.LocalMediaCursor
import com.mineg.mobile.bridge.library.model.LocalMediaPage
import com.mineg.mobile.bridge.media.model.PrivateMediaPage
import com.mineg.mobile.bridge.media.model.PrivateMediaDetail
import com.mineg.mobile.bridge.media.model.PrivateMediaView
import com.mineg.mobile.bridge.media.model.PrivateMediaSaveResult
import com.mineg.mobile.bridge.media.model.PrivateMediaTrashResult
import com.mineg.mobile.bridge.shared.model.PrivateMediaShareResult
import com.mineg.mobile.bridge.shared.model.SharedMediaPage
import com.mineg.mobile.bridge.shared.model.SharedMediaDetail
import com.mineg.mobile.bridge.shared.model.TrashMediaPage
import com.mineg.mobile.bridge.shared.model.TrashMediaRestoreResult
import com.mineg.mobile.bridge.shared.model.FeedbackSubmissionResult
import com.mineg.mobile.bridge.account.Profile
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import com.mineg.mobile.core.effect.PlatformEffectDispatcher
import com.mineg.mobile.feature.private_media.PrivateMediaLocalSaver
import com.mineg.mobile.platform.AndroidFilePort
import com.mineg.mobile.platform.AndroidConnectivityPort
import com.mineg.mobile.platform.AndroidMediaSourcePort
import com.mineg.mobile.platform.AndroidMediaPlaybackPort
import com.mineg.mobile.platform.AndroidSecureStorePort
import com.mineg.mobile.platform.AndroidSystemAlbumWriterPort
import com.mineg.mobile.platform.AndroidTransportPort
import com.mineg.mobile.platform.PrivateThumbnailCacheKeys
import com.mineg.mobile.platform.PrivateThumbnailDiskCache
import com.mineg.mobile.platform.PrivateOriginalDiskStore
import com.mineg.mobile.platform.logging.MediaLoadLog
import com.mineg.mobile.platform.work.AndroidBackupScheduler
import com.mineg.mobile.presentation.LibraryAccess
import java.io.File
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Defines the use cases available to presentation without exposing platform or Core details. */
internal interface MineGAppRuntime : AutoCloseable {
  /** Restores the locally persisted account route, if a session is still valid. */
  suspend fun restoreSession(): AccountRouteSnapshot?
  /** Authenticates an existing account and returns Core's next route. */
  suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountRouteSnapshot
  /** Creates an account and returns the review/home route selected by Core. */
  suspend fun signUp(phone: String, password: String): AccountRouteSnapshot
  /** Refreshes the current account's approval state. */
  suspend fun refreshReviewStatus(): ApprovalStatus
  /** Loads the authoritative profile, optionally allowing Core's cache. */
  suspend fun loadProfile(userId: String, allowCached: Boolean): Profile
  /** Updates editable profile text. */
  suspend fun updateProfile(nickname: String): Profile
  /** Prepares and uploads the image selected by [uri] as the account avatar. */
  suspend fun updateAvatar(uri: Uri): Profile
  /** Refreshes the first private-media page. */
  suspend fun refreshPrivateMedia(limit: Int = 50): PrivateMediaPage
  /** Loads the next private-media page. */
  suspend fun loadMorePrivateMedia(limit: Int = 50): PrivateMediaPage
  /** Reads the current private-media snapshot without forcing a refresh. */
  suspend fun getPrivateMediaPage(limit: Int = 100): PrivateMediaPage?
  /** Loads full private-media metadata and its resource manifest. */
  suspend fun getPrivateMediaDetail(mediaId: String): PrivateMediaDetail
  /** Resolves or materializes an integrity-checked local original for still images. */
  suspend fun resolvePrivateMediaOriginal(userId: String, detail: PrivateMediaDetail): String?
  /** Opens a private-media preview, reusing the account-isolated cache when possible. */
  suspend fun openPrivateMedia(userId: String, mediaId: String): PrivateMediaView
  /** Closes a Core or locally cached private-media view. */
  suspend fun closePrivateMedia(viewHandle: String): Boolean
  /** Evicts one item's disk and in-memory thumbnail. */
  suspend fun invalidatePrivateMediaThumbnail(userId: String, mediaId: String)
  /** Saves verified private-media resources to the Android system album. */
  suspend fun savePrivateMediaToSystemAlbum(userId: String, detail: PrivateMediaDetail): PrivateMediaSaveResult
  /** Moves one owned item to the recycle bin and evicts local copies. */
  suspend fun trashPrivateMedia(mediaId: String): PrivateMediaTrashResult
  /** Changes the shared-space visibility of one private item. */
  suspend fun setPrivateMediaShare(mediaId: String, shared: Boolean): PrivateMediaShareResult
  /** Refreshes or paginates the shared-media feed. */
  suspend fun refreshSharedMedia(filter: String = "all", cursor: String? = null, limit: Int = 50): SharedMediaPage
  /** Loads complete shared-media metadata. */
  suspend fun getSharedMediaDetail(mediaId: String): SharedMediaDetail
  /** Opens a verified shared-media preview. */
  suspend fun openSharedMedia(mediaId: String): PrivateMediaView
  /** Closes a verified shared-media preview. */
  suspend fun closeSharedMedia(viewHandle: String): Boolean
  /** Refreshes or paginates the recycle bin. */
  suspend fun refreshTrashMedia(cursor: String? = null, limit: Int = 50): TrashMediaPage
  /** Restores one recycle-bin item. */
  suspend fun restoreTrashMedia(mediaId: String): TrashMediaRestoreResult
  /** Submits user feedback with the current app and OS diagnostics. */
  suspend fun sendFeedback(
    category: String,
    description: String,
    contact: String,
    appVersion: String,
    osVersion: String,
  ): FeedbackSubmissionResult
  /** Loads a committed local-library generation and all album summaries. */
  suspend fun loadLocalLibrary(userId: String, forceRefresh: Boolean = false): LocalLibrarySnapshot
  /** Reads backup preferences for this installation. */
  suspend fun getBackupSettings(userId: String): BackupSettings
  /** Persists backup preferences and reconciles background scheduling. */
  suspend fun updateBackupSettings(userId: String, settings: BackupSettings): BackupSettings
  /** Reads aggregate backup progress. */
  suspend fun getBackupOverview(userId: String): BackupOverview
  /** Reads per-item backup state for one album. */
  suspend fun getLocalAlbumBackupProgress(userId: String, albumRef: String): LocalAlbumBackupProgress
  /** Starts debounced observation of device-library changes. */
  fun startBackupChangeObservation(userId: String)
  /** Lists one bounded page of local media within an album. */
  suspend fun listLocalMedia(
    userId: String,
    albumRef: String,
    cursor: LocalMediaCursor? = null,
    limit: Int = 120,
  ): LocalMediaPage
  /** Adds one user-selected local item to the durable manual backup queue. */
  suspend fun enqueueBackupMedia(userId: String, platformAssetRef: String)
  /** Signs out and clears all account-scoped local media artifacts. */
  suspend fun signOut()
  /** Maps the platform permission snapshot to presentation's access state. */
  fun libraryAccess(): LibraryAccess
  /** Persists that Android has already requested library permission. */
  fun markLibraryPermissionRequested()
}

internal data class LocalLibrarySnapshot(
  val summary: LocalLibrarySummary,
  val albums: List<LocalAlbum>,
)

/** Production composition root implementing runtime use cases with Android platform adapters. */
internal class AndroidMineGAppRuntime(context: Context) : MineGAppRuntime {
  private val applicationContext = context.applicationContext
  private val core = CoreClient().apply {
    initialize(applicationContext.getDatabasePath("mineg-core.db").absolutePath)
  }
  private val mediaSource = AndroidMediaSourcePort(applicationContext)
  private val secureStore = AndroidSecureStorePort(applicationContext)
  private val transport = AndroidTransportPort(BuildConfig.MINEG_API_BASE_URL, allowPrivateHttp = BuildConfig.DEBUG)
  private val files = AndroidFilePort(applicationContext).also { filePort ->
    val removed = filePort.deleteOrphanedPrivateViewFiles()
    if (removed > 0) MediaLoadLog.debug("preview-cache removed-orphans=$removed")
  }
  private val thumbnailCache = PrivateThumbnailDiskCache(
    File(applicationContext.cacheDir, "mineg-thumbnails-v1"),
  )
  private val privateOriginals = PrivateOriginalDiskStore(
    File(applicationContext.noBackupFilesDir, "mineg-originals-v1"),
  )
  private val connectivity = AndroidConnectivityPort(applicationContext)
  private val mediaPlayback = AndroidMediaPlaybackPort(applicationContext)
  private val systemAlbum = AndroidSystemAlbumWriterPort(applicationContext)
  private val backupScheduler = AndroidBackupScheduler(applicationContext)
  private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val stage05Dispatcher = Dispatchers.IO.limitedParallelism(1)
  private var scheduledBackupUserId: String? = null
  private var activeAccountId: String? = null
  private val cachedThumbnailHandles = ConcurrentHashMap<String, String>()
  private var libraryChangeObserver: AutoCloseable? = null
  private var libraryChangeJob: Job? = null
  private val dispatcher = PlatformEffectDispatcher(
    transport,
    secureStore,
    mediaSource,
    files,
    connectivity,
    systemAlbum,
    mediaPlayback,
  )
  private val operationRunner = CoreOperationRunner(core, dispatcher)
  private val accountGateway = AccountCoreGateway(core, operationRunner)
  private val profileGateway = ProfileCoreGateway(core, operationRunner)
  private val localLibraryGateway = LocalLibraryCoreGateway(core, operationRunner)
  private val backupSettingsGateway = BackupSettingsCoreGateway(core)
  private val backupQueueGateway = BackupQueueCoreGateway(core, operationRunner)
  private val privateMediaGateway = PrivateMediaCoreGateway(core, operationRunner)
  private val sharedMediaGateway = SharedMediaCoreGateway(core, operationRunner)
  private val trashGateway = TrashCoreGateway(core, operationRunner)
  private val feedbackGateway = FeedbackCoreGateway(core, operationRunner)
  private val privateMediaLocalSaver = PrivateMediaLocalSaver(privateOriginals, systemAlbum, privateMediaGateway)

  override suspend fun restoreSession(): AccountRouteSnapshot? = accountGateway.restoreSession().also {
    activeAccountId = it?.userId
  }

  override suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountRouteSnapshot {
    return accountGateway.signIn(phone, password, agreementAccepted).also { activeAccountId = it.userId }
  }

  override suspend fun signUp(phone: String, password: String): AccountRouteSnapshot {
    val passwordBytes = password.toByteArray()
    return try {
      accountGateway.signUp(phone, passwordBytes, UUID.randomUUID().toString())
        .also { activeAccountId = it.userId }
    } finally {
      passwordBytes.fill(0)
    }
  }

  override suspend fun refreshReviewStatus(): ApprovalStatus = accountGateway.refreshReviewStatus()

  override suspend fun loadProfile(userId: String, allowCached: Boolean): Profile =
    accountGateway.getProfile(allowCached).also { require(it.id == userId) }

  override suspend fun updateProfile(nickname: String): Profile = accountGateway.updateProfile(nickname)

  override suspend fun updateAvatar(uri: Uri): Profile = withContext(Dispatchers.IO) {
    val resolver = applicationContext.contentResolver
    val sourceSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getLong(0) else -1L
    } ?: -1L
    require(sourceSize in 1..10L * 1024 * 1024) { "avatar source size invalid" }
    val source = ImageDecoder.createSource(resolver, uri)
    val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      val largest = max(info.size.width, info.size.height)
      if (largest > 2048) decoder.setTargetSampleSize(ceil(largest / 2048.0).toInt())
    }
    // Center-crop before scaling so avatar geometry stays deterministic across source ratios.
    val side = min(decoded.width, decoded.height)
    val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
    val targetSide = side.coerceAtMost(1024)
    val display = if (cropped.width == targetSide) cropped else Bitmap.createScaledBitmap(cropped, targetSide, targetSide, true)
    if (cropped !== decoded && decoded !== display) decoded.recycle()
    if (display !== cropped) cropped.recycle()
    val output = ByteArrayOutputStream()
    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    check(display.compress(format, 90, output))
    display.recycle()
    val bytes = output.toByteArray()
    require(bytes.size <= 10 * 1024 * 1024) { "avatar display size invalid" }
    try {
      profileGateway.updateAvatar(bytes, sourceSize, targetSide)
    } finally {
      bytes.fill(0)
    }
  }

  override suspend fun refreshPrivateMedia(limit: Int): PrivateMediaPage = withContext(stage05Dispatcher) {
    privateMediaGateway.refreshPrivateMedia(limit, allowCached = true)
  }

  override suspend fun loadMorePrivateMedia(limit: Int): PrivateMediaPage = withContext(stage05Dispatcher) {
    privateMediaGateway.loadMorePrivateMedia(limit, allowCached = false)
  }

  override suspend fun getPrivateMediaPage(limit: Int): PrivateMediaPage? = withContext(stage05Dispatcher) {
    privateMediaGateway.getPrivateMediaPage(limit)
  }

  override suspend fun getPrivateMediaDetail(mediaId: String): PrivateMediaDetail = withContext(stage05Dispatcher) {
    privateMediaGateway.getPrivateMediaDetail(mediaId)
  }

  override suspend fun resolvePrivateMediaOriginal(
    userId: String,
    detail: PrivateMediaDetail,
  ): String? = withContext(stage05Dispatcher) {
    require(activeAccountId == userId) { "private original account does not match the active session" }
    if (detail.mediaType == "VIDEO") return@withContext null
    val original = detail.resources.firstOrNull {
      it.resourceType == "ORIGINAL" && it.mimeType.startsWith("image/")
    } ?: return@withContext null

    // Prefer the device library, then the verified private cache, and download only as a fallback.
    detail.localPlatformAssetRef?.let { assetRef ->
      mediaSource.resolveAvailableMediaUri(assetRef)?.let { sourceUri ->
        MediaLoadLog.trace("private-original local-hit media=${MediaLoadLog.mediaRef(detail.id)}")
        return@withContext sourceUri
      }
    }

    privateOriginals.get(userId, detail.id, original.contentSize, original.contentSha256)?.let { cached ->
      MediaLoadLog.trace("private-original disk-hit media=${MediaLoadLog.mediaRef(detail.id)}")
      return@withContext Uri.fromFile(cached).toString()
    }

    MediaLoadLog.trace("private-original disk-miss media=${MediaLoadLog.mediaRef(detail.id)}")
    val view = privateMediaGateway.openPrivateMedia(detail.id, variant = "DETAIL")
    try {
      if (view.resourceType != "ORIGINAL" || view.mimeType != original.mimeType) {
        return@withContext null
      }
      val sourceFile = Uri.parse(view.sourceUri)
        .takeIf { it.scheme == "file" }
        ?.path
        ?.let(::File)
        ?: return@withContext null
      privateOriginals.put(
        userId,
        detail.id,
        sourceFile,
        original.contentSize,
        original.contentSha256,
      )?.let { stored ->
        MediaLoadLog.trace("private-original disk-store media=${MediaLoadLog.mediaRef(detail.id)}")
        Uri.fromFile(stored).toString()
      }
    } finally {
      runCatching { privateMediaGateway.closePrivateMedia(view.viewHandle) }
    }
  }

  override suspend fun openPrivateMedia(userId: String, mediaId: String): PrivateMediaView = withContext(stage05Dispatcher) {
    require(activeAccountId == userId) { "thumbnail cache account does not match the active session" }
    thumbnailCache.get(userId, mediaId)?.let { cached ->
      val opened = mediaPlayback.openCachedThumbnail(cached)
      thumbnailCache.retain(cached.cacheKey)
      cachedThumbnailHandles[opened.viewHandle] = cached.cacheKey
      MediaLoadLog.trace("preview-cache disk-hit media=${MediaLoadLog.mediaRef(mediaId)}")
      return@withContext PrivateMediaView(
        mediaId = mediaId,
        resourceType = cached.resourceType,
        mimeType = cached.mimeType,
        viewHandle = opened.viewHandle,
        sourceUri = opened.sourceUri,
      )
    }
    MediaLoadLog.trace("preview-cache disk-miss media=${MediaLoadLog.mediaRef(mediaId)}")
    // Core verifies a cache miss before the temporary resource is copied into the disk cache.
    privateMediaGateway.openPrivateMedia(mediaId).also { view ->
      val sourceFile = runCatching {
        Uri.parse(view.sourceUri).takeIf { it.scheme == "file" }?.path?.let(::File)
      }.getOrNull()
      if (sourceFile != null) {
        val cached = thumbnailCache.put(userId, mediaId, sourceFile, view.resourceType, view.mimeType)
        MediaLoadLog.trace(
          "preview-cache disk-store media=${MediaLoadLog.mediaRef(mediaId)} stored=${cached != null}",
        )
      }
    }
  }

  override suspend fun closePrivateMedia(viewHandle: String): Boolean = withContext(stage05Dispatcher) {
    val cacheKey = cachedThumbnailHandles.remove(viewHandle)
    if (cacheKey == null) {
      privateMediaGateway.closePrivateMedia(viewHandle)
    } else {
      try {
        mediaPlayback.closeVerifiedMedia(viewHandle)
      } finally {
        thumbnailCache.release(cacheKey)
      }
    }
  }

  override suspend fun invalidatePrivateMediaThumbnail(userId: String, mediaId: String) {
    withContext(stage05Dispatcher) {
      thumbnailCache.remove(userId, mediaId)
      applicationContext.imageLoader.memoryCache?.remove(
        MemoryCache.Key(PrivateThumbnailCacheKeys.memoryKey(userId, mediaId)),
      )
    }
  }

  override suspend fun savePrivateMediaToSystemAlbum(
    userId: String,
    detail: PrivateMediaDetail,
  ): PrivateMediaSaveResult = withContext(stage05Dispatcher) {
    require(activeAccountId == userId) { "private-media save account does not match the active session" }
    privateMediaLocalSaver.save(userId, detail)
  }

  override suspend fun trashPrivateMedia(mediaId: String): PrivateMediaTrashResult = withContext(stage05Dispatcher) {
    privateMediaGateway.trashPrivateMedia(mediaId).also {
      activeAccountId?.let { userId ->
        privateOriginals.remove(userId, mediaId)
        thumbnailCache.remove(userId, mediaId)
        applicationContext.imageLoader.memoryCache?.remove(
          MemoryCache.Key(PrivateThumbnailCacheKeys.memoryKey(userId, mediaId)),
        )
      }
    }
  }

  override suspend fun setPrivateMediaShare(mediaId: String, shared: Boolean): PrivateMediaShareResult =
    withContext(stage05Dispatcher) { sharedMediaGateway.setPrivateMediaShare(mediaId, shared) }

  override suspend fun refreshSharedMedia(
    filter: String,
    cursor: String?,
    limit: Int,
  ): SharedMediaPage = withContext(stage05Dispatcher) {
    sharedMediaGateway.refreshSharedMedia(filter, cursor, limit)
  }

  override suspend fun getSharedMediaDetail(mediaId: String): SharedMediaDetail =
    withContext(stage05Dispatcher) { sharedMediaGateway.getSharedMediaDetail(mediaId) }

  override suspend fun openSharedMedia(mediaId: String): PrivateMediaView =
    withContext(stage05Dispatcher) { sharedMediaGateway.openSharedMedia(mediaId) }

  override suspend fun closeSharedMedia(viewHandle: String): Boolean =
    withContext(stage05Dispatcher) { sharedMediaGateway.closeSharedMedia(viewHandle) }

  override suspend fun refreshTrashMedia(cursor: String?, limit: Int): TrashMediaPage =
    withContext(stage05Dispatcher) { trashGateway.refreshTrash(cursor, limit) }

  override suspend fun restoreTrashMedia(mediaId: String): TrashMediaRestoreResult =
    withContext(stage05Dispatcher) { trashGateway.restoreTrash(mediaId) }

  override suspend fun sendFeedback(
    category: String,
    description: String,
    contact: String,
    appVersion: String,
    osVersion: String,
  ): FeedbackSubmissionResult = withContext(stage05Dispatcher) {
    feedbackGateway.sendFeedback(category, description, contact, appVersion, osVersion)
  }

  override suspend fun loadLocalLibrary(userId: String, forceRefresh: Boolean): LocalLibrarySnapshot = withContext(Dispatchers.IO) {
    val current = localLibraryGateway.getSummary(userId)
    val summary = if (forceRefresh || current == null) {
      localLibraryGateway.startForegroundScan(userId)
    } else {
      current
    }
    getBackupSettings(userId).also { settings ->
      if (settings.autoBackupEnabled) {
        scheduledBackupUserId = userId
        backupScheduler.schedule(userId, settings.allowCellularBackup, ensurePeriodicReconciliation = true)
      }
    }
    val albums = mutableListOf<LocalAlbum>()
    var albumCursor: com.mineg.mobile.bridge.library.model.AlbumCursor? = null
    // Consume all album pages here so presentation receives one coherent generation snapshot.
    do {
      val page = localLibraryGateway.listAlbums(userId, cursor = albumCursor, limit = 100)
      albums += page.items
      check(page.nextCursor == null || page.nextCursor != albumCursor) { "local album cursor did not advance" }
      albumCursor = page.nextCursor
    } while (albumCursor != null)
    LocalLibrarySnapshot(summary = summary, albums = albums)
  }

  override suspend fun getBackupSettings(userId: String): BackupSettings = withContext(Dispatchers.IO) {
    backupSettingsGateway.getSettings(userId, deviceInstallationId())
  }

  override suspend fun updateBackupSettings(
    userId: String,
    settings: BackupSettings,
  ): BackupSettings = withContext(Dispatchers.IO) {
    backupSettingsGateway.updateSettings(userId, deviceInstallationId(), settings).also { confirmed ->
      if (confirmed.autoBackupEnabled) {
        scheduledBackupUserId = userId
        backupScheduler.schedule(userId, confirmed.allowCellularBackup, ensurePeriodicReconciliation = true)
      } else {
        backupScheduler.cancelPeriodicReconciliation(userId)
        // Disabling automatic backup pauses only automatic work in Core.  A user-initiated
        // one-off upload is durable work of its own and must keep its execution window.
        val summary = backupQueueGateway.getBackupQueueSummary(userId, deviceInstallationId())
        if (summary.manualPendingCount > 0) {
          scheduledBackupUserId = userId
          backupScheduler.schedule(userId, confirmed.allowCellularBackup)
        } else {
          backupScheduler.cancel(userId)
          if (scheduledBackupUserId == userId) scheduledBackupUserId = null
        }
      }
    }
  }

  override suspend fun listLocalMedia(
    userId: String,
    albumRef: String,
    cursor: LocalMediaCursor?,
    limit: Int,
  ): LocalMediaPage =
    withContext(Dispatchers.IO) {
      localLibraryGateway.listMedia(userId, albumRef, cursor = cursor, limit = limit)
    }

  override suspend fun enqueueBackupMedia(userId: String, platformAssetRef: String) = withContext(Dispatchers.IO) {
    backupQueueGateway.enqueueBackupMedia(userId, deviceInstallationId(), platformAssetRef)
    val settings = backupSettingsGateway.getSettings(userId, deviceInstallationId())
    scheduledBackupUserId = userId
    backupScheduler.schedule(userId, settings.allowCellularBackup, ensurePeriodicReconciliation = settings.autoBackupEnabled)
  }

  /** Reconciles the durable queue for WorkManager without exposing gateway details. */
  internal suspend fun reconcileBackupQueue(userId: String) = withContext(Dispatchers.IO) {
    backupQueueGateway.reconcileBackupQueue(userId, deviceInstallationId())
  }

  /** Executes one bounded background backup cycle. */
  internal suspend fun runBackupCycle(userId: String): BackupCycleResult = withContext(Dispatchers.IO) {
    backupQueueGateway.runBackupCycle(userId, deviceInstallationId())
  }

  /** Reads scheduler-facing queue counters. */
  internal suspend fun getBackupQueueSummary(userId: String): BackupQueueSummary = withContext(Dispatchers.IO) {
    backupQueueGateway.getBackupQueueSummary(userId, deviceInstallationId())
  }

  override suspend fun getBackupOverview(userId: String): BackupOverview = withContext(Dispatchers.IO) {
    backupQueueGateway.getBackupOverview(userId, deviceInstallationId())
  }

  override suspend fun getLocalAlbumBackupProgress(
    userId: String,
    albumRef: String,
  ): LocalAlbumBackupProgress = withContext(Dispatchers.IO) {
    backupQueueGateway.getLocalAlbumBackupProgress(userId, deviceInstallationId(), albumRef)
  }

  override fun startBackupChangeObservation(userId: String) {
    libraryChangeObserver?.close()
    libraryChangeObserver = mediaSource.observeLibraryChanges {
      libraryChangeJob?.cancel()
      // Debounce bursty MediaStore notifications into one Core invalidation and schedule update.
      libraryChangeJob = runtimeScope.launch {
        delay(1_000)
        backupQueueGateway.notifyLibraryChanged(userId, deviceInstallationId())
        val settings = backupSettingsGateway.getSettings(userId, deviceInstallationId())
        if (settings.autoBackupEnabled) {
          backupScheduler.schedule(userId, settings.allowCellularBackup, ensurePeriodicReconciliation = true)
        }
      }
    }
  }

  override suspend fun signOut() {
    val accountId = activeAccountId
    scheduledBackupUserId?.let(backupScheduler::cancel)
    scheduledBackupUserId = null
    libraryChangeObserver?.close()
    libraryChangeObserver = null
    libraryChangeJob?.cancel()
    try {
      accountGateway.signOut()
    } finally {
      accountId?.let { userId ->
        privateOriginals.clearAccount(userId)
        thumbnailCache.clearAccount(userId)
        val prefix = PrivateThumbnailCacheKeys.accountPrefix(userId)
        applicationContext.imageLoader.memoryCache?.let { memoryCache ->
          memoryCache.keys.filter { it.key.startsWith(prefix) }.forEach(memoryCache::remove)
        }
      }
      activeAccountId = null
    }
  }

  override fun libraryAccess(): LibraryAccess = when (mediaSource.getPermissionSnapshot().library) {
    LibraryPermissionState.NOT_DETERMINED -> LibraryAccess.NOT_DETERMINED
    LibraryPermissionState.FULL -> LibraryAccess.FULL
    LibraryPermissionState.LIMITED -> LibraryAccess.LIMITED
    LibraryPermissionState.RESTRICTED -> LibraryAccess.RESTRICTED
    LibraryPermissionState.DENIED -> LibraryAccess.DENIED
    LibraryPermissionState.SYSTEM_RESTRICTED -> LibraryAccess.SYSTEM_RESTRICTED
  }

  override fun markLibraryPermissionRequested() {
    mediaSource.markPermissionRequested()
  }

  /** Reads the device installation identifier stored by Core in the encrypted store. */
  private fun deviceInstallationId(): String =
    checkNotNull(secureStore.readSecret("device.installationId"))
      .toString(Charsets.UTF_8)
      .also { require(it.isNotBlank()) }

  override fun close() {
    libraryChangeObserver?.close()
    libraryChangeJob?.cancel()
    runtimeScope.cancel()
    cachedThumbnailHandles.forEach { (handle, cacheKey) ->
      runCatching { mediaPlayback.closeVerifiedMedia(handle) }
      thumbnailCache.release(cacheKey)
    }
    cachedThumbnailHandles.clear()
    dispatcher.close()
    core.close()
  }
}

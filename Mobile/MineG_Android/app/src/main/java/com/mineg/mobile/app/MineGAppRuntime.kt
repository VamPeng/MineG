package com.mineg.mobile.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import coil3.imageLoader
import coil3.memory.MemoryCache
import com.mineg.mobile.BuildConfig
import com.mineg.mobile.account.CoreAccountClient
import com.mineg.mobile.account.CoreStage02Client
import com.mineg.mobile.account.CoreStage04Client
import com.mineg.mobile.account.CoreStage05Client
import com.mineg.mobile.account.CoreStage06Client
import com.mineg.mobile.account.BackupCycleResult
import com.mineg.mobile.account.BackupQueueSummary
import com.mineg.mobile.account.BackupOverview
import com.mineg.mobile.account.LocalAlbumBackupProgress
import com.mineg.mobile.contracts.AccountRouteSnapshot
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.LibraryPermissionState
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.BackupSettings
import com.mineg.mobile.contracts.LocalLibrarySummary
import com.mineg.mobile.contracts.LocalMedia
import com.mineg.mobile.contracts.LocalMediaCursor
import com.mineg.mobile.contracts.LocalMediaPage
import com.mineg.mobile.contracts.PrivateMediaPage
import com.mineg.mobile.contracts.PrivateMediaDetail
import com.mineg.mobile.contracts.PrivateMediaView
import com.mineg.mobile.contracts.PrivateMediaSaveResult
import com.mineg.mobile.contracts.PrivateMediaTrashResult
import com.mineg.mobile.contracts.PrivateMediaShareResult
import com.mineg.mobile.contracts.FamilyMediaPage
import com.mineg.mobile.contracts.FamilyMediaDetail
import com.mineg.mobile.contracts.TrashMediaPage
import com.mineg.mobile.contracts.TrashMediaRestoreResult
import com.mineg.mobile.contracts.FeedbackSubmissionResult
import com.mineg.mobile.contracts.Profile
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import com.mineg.mobile.core.PlatformEffectDispatcher
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

internal interface MineGAppRuntime : AutoCloseable {
  suspend fun restoreSession(): AccountRouteSnapshot?
  suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountRouteSnapshot
  suspend fun signUp(phone: String, password: String): AccountRouteSnapshot
  suspend fun refreshReviewStatus(): ApprovalStatus
  suspend fun loadProfile(userId: String, allowCached: Boolean): Profile
  suspend fun updateProfile(nickname: String): Profile
  suspend fun updateAvatar(uri: Uri): Profile
  suspend fun refreshPrivateMedia(limit: Int = 50): PrivateMediaPage
  suspend fun loadMorePrivateMedia(limit: Int = 50): PrivateMediaPage
  suspend fun getPrivateMediaPage(limit: Int = 100): PrivateMediaPage?
  suspend fun getPrivateMediaDetail(mediaId: String): PrivateMediaDetail
  suspend fun resolvePrivateMediaOriginal(userId: String, detail: PrivateMediaDetail): String?
  suspend fun openPrivateMedia(userId: String, mediaId: String): PrivateMediaView
  suspend fun closePrivateMedia(viewHandle: String): Boolean
  suspend fun invalidatePrivateMediaThumbnail(userId: String, mediaId: String)
  suspend fun savePrivateMediaToSystemAlbum(userId: String, detail: PrivateMediaDetail): PrivateMediaSaveResult
  suspend fun trashPrivateMedia(mediaId: String): PrivateMediaTrashResult
  suspend fun setPrivateMediaShare(mediaId: String, shared: Boolean): PrivateMediaShareResult
  suspend fun refreshFamilyMedia(filter: String = "all", cursor: String? = null, limit: Int = 50): FamilyMediaPage
  suspend fun getFamilyMediaDetail(mediaId: String): FamilyMediaDetail
  suspend fun openFamilyMedia(mediaId: String): PrivateMediaView
  suspend fun closeFamilyMedia(viewHandle: String): Boolean
  suspend fun refreshTrashMedia(cursor: String? = null, limit: Int = 50): TrashMediaPage
  suspend fun restoreTrashMedia(mediaId: String): TrashMediaRestoreResult
  suspend fun sendFeedback(
    category: String,
    description: String,
    contact: String,
    appVersion: String,
    osVersion: String,
  ): FeedbackSubmissionResult
  suspend fun loadLocalLibrary(userId: String, forceRefresh: Boolean = false): LocalLibrarySnapshot
  suspend fun getBackupSettings(userId: String): BackupSettings
  suspend fun updateBackupSettings(userId: String, settings: BackupSettings): BackupSettings
  suspend fun getBackupOverview(userId: String): BackupOverview
  suspend fun getLocalAlbumBackupProgress(userId: String, albumRef: String): LocalAlbumBackupProgress
  fun startBackupChangeObservation(userId: String)
  suspend fun listLocalMedia(
    userId: String,
    albumRef: String,
    cursor: LocalMediaCursor? = null,
    limit: Int = 120,
  ): LocalMediaPage
  suspend fun enqueueBackupMedia(userId: String, platformAssetRef: String)
  suspend fun signOut()
  fun libraryAccess(): LibraryAccess
  fun markLibraryPermissionRequested()
}

internal data class LocalLibrarySnapshot(
  val summary: LocalLibrarySummary,
  val albums: List<LocalAlbum>,
)

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
  private val coreAccount = CoreAccountClient(core, CoreOperationRunner(core, dispatcher))
  private val coreStage02 = CoreStage02Client(core, CoreOperationRunner(core, dispatcher))
  private val coreStage04 = CoreStage04Client(core, CoreOperationRunner(core, dispatcher))
  private val coreStage05 = CoreStage05Client(core, CoreOperationRunner(core, dispatcher))
  private val coreStage06 = CoreStage06Client(core, CoreOperationRunner(core, dispatcher))
  private val privateMediaLocalSaver = PrivateMediaLocalSaver(privateOriginals, systemAlbum, coreStage05)

  override suspend fun restoreSession(): AccountRouteSnapshot? = coreAccount.restoreSession().also {
    activeAccountId = it?.userId
  }

  override suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountRouteSnapshot {
    return coreAccount.signIn(phone, password, agreementAccepted).also { activeAccountId = it.userId }
  }

  override suspend fun signUp(phone: String, password: String): AccountRouteSnapshot {
    val passwordBytes = password.toByteArray()
    return try {
      coreAccount.signUp(phone, passwordBytes, UUID.randomUUID().toString())
        .also { activeAccountId = it.userId }
    } finally {
      passwordBytes.fill(0)
    }
  }

  override suspend fun refreshReviewStatus(): ApprovalStatus = coreAccount.refreshReviewStatus()

  override suspend fun loadProfile(userId: String, allowCached: Boolean): Profile =
    coreAccount.getProfile(allowCached).also { require(it.id == userId) }

  override suspend fun updateProfile(nickname: String): Profile = coreAccount.updateProfile(nickname)

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
      coreStage02.updateAvatar(bytes, sourceSize, targetSide)
    } finally {
      bytes.fill(0)
    }
  }

  override suspend fun refreshPrivateMedia(limit: Int): PrivateMediaPage = withContext(stage05Dispatcher) {
    coreStage05.refreshPrivateMedia(limit, allowCached = true)
  }

  override suspend fun loadMorePrivateMedia(limit: Int): PrivateMediaPage = withContext(stage05Dispatcher) {
    coreStage05.loadMorePrivateMedia(limit, allowCached = false)
  }

  override suspend fun getPrivateMediaPage(limit: Int): PrivateMediaPage? = withContext(stage05Dispatcher) {
    coreStage05.getPrivateMediaPage(limit)
  }

  override suspend fun getPrivateMediaDetail(mediaId: String): PrivateMediaDetail = withContext(stage05Dispatcher) {
    coreStage05.getPrivateMediaDetail(mediaId)
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
    val view = coreStage05.openPrivateMedia(detail.id, variant = "DETAIL")
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
      runCatching { coreStage05.closePrivateMedia(view.viewHandle) }
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
    coreStage05.openPrivateMedia(mediaId).also { view ->
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
      coreStage05.closePrivateMedia(viewHandle)
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
    coreStage05.trashPrivateMedia(mediaId).also {
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
    withContext(stage05Dispatcher) { coreStage06.setPrivateMediaShare(mediaId, shared) }

  override suspend fun refreshFamilyMedia(
    filter: String,
    cursor: String?,
    limit: Int,
  ): FamilyMediaPage = withContext(stage05Dispatcher) {
    coreStage06.refreshFamilyMedia(filter, cursor, limit)
  }

  override suspend fun getFamilyMediaDetail(mediaId: String): FamilyMediaDetail =
    withContext(stage05Dispatcher) { coreStage06.getFamilyMediaDetail(mediaId) }

  override suspend fun openFamilyMedia(mediaId: String): PrivateMediaView =
    withContext(stage05Dispatcher) { coreStage06.openFamilyMedia(mediaId) }

  override suspend fun closeFamilyMedia(viewHandle: String): Boolean =
    withContext(stage05Dispatcher) { coreStage06.closeFamilyMedia(viewHandle) }

  override suspend fun refreshTrashMedia(cursor: String?, limit: Int): TrashMediaPage =
    withContext(stage05Dispatcher) { coreStage06.refreshTrash(cursor, limit) }

  override suspend fun restoreTrashMedia(mediaId: String): TrashMediaRestoreResult =
    withContext(stage05Dispatcher) { coreStage06.restoreTrash(mediaId) }

  override suspend fun sendFeedback(
    category: String,
    description: String,
    contact: String,
    appVersion: String,
    osVersion: String,
  ): FeedbackSubmissionResult = withContext(stage05Dispatcher) {
    coreStage06.sendFeedback(category, description, contact, appVersion, osVersion)
  }

  override suspend fun loadLocalLibrary(userId: String, forceRefresh: Boolean): LocalLibrarySnapshot = withContext(Dispatchers.IO) {
    val current = coreStage02.getLocalLibrarySummary(userId)
    val summary = if (forceRefresh || current == null) {
      coreStage02.startForegroundLocalScan(userId)
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
    var albumCursor: com.mineg.mobile.contracts.AlbumCursor? = null
    do {
      val page = coreStage02.listLocalAlbums(userId, cursor = albumCursor, limit = 100)
      albums += page.items
      check(page.nextCursor == null || page.nextCursor != albumCursor) { "local album cursor did not advance" }
      albumCursor = page.nextCursor
    } while (albumCursor != null)
    LocalLibrarySnapshot(summary = summary, albums = albums)
  }

  override suspend fun getBackupSettings(userId: String): BackupSettings = withContext(Dispatchers.IO) {
    coreStage02.getBackupSettings(userId, deviceInstallationId())
  }

  override suspend fun updateBackupSettings(
    userId: String,
    settings: BackupSettings,
  ): BackupSettings = withContext(Dispatchers.IO) {
    coreStage02.updateBackupSettings(userId, deviceInstallationId(), settings).also { confirmed ->
      if (confirmed.autoBackupEnabled) {
        scheduledBackupUserId = userId
        backupScheduler.schedule(userId, confirmed.allowCellularBackup, ensurePeriodicReconciliation = true)
      } else {
        backupScheduler.cancelPeriodicReconciliation(userId)
        // Disabling automatic backup pauses only automatic work in Core.  A user-initiated
        // one-off upload is durable work of its own and must keep its execution window.
        val summary = coreStage04.getBackupQueueSummary(userId, deviceInstallationId())
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
      coreStage02.listLocalMedia(userId, albumRef, cursor = cursor, limit = limit)
    }

  override suspend fun enqueueBackupMedia(userId: String, platformAssetRef: String) = withContext(Dispatchers.IO) {
    coreStage04.enqueueBackupMedia(userId, deviceInstallationId(), platformAssetRef)
    val settings = coreStage02.getBackupSettings(userId, deviceInstallationId())
    scheduledBackupUserId = userId
    backupScheduler.schedule(userId, settings.allowCellularBackup, ensurePeriodicReconciliation = settings.autoBackupEnabled)
  }

  internal suspend fun reconcileBackupQueue(userId: String) = withContext(Dispatchers.IO) {
    coreStage04.reconcileBackupQueue(userId, deviceInstallationId())
  }

  internal suspend fun runBackupCycle(userId: String): BackupCycleResult = withContext(Dispatchers.IO) {
    coreStage04.runBackupCycle(userId, deviceInstallationId())
  }

  internal suspend fun getBackupQueueSummary(userId: String): BackupQueueSummary = withContext(Dispatchers.IO) {
    coreStage04.getBackupQueueSummary(userId, deviceInstallationId())
  }

  override suspend fun getBackupOverview(userId: String): BackupOverview = withContext(Dispatchers.IO) {
    coreStage04.getBackupOverview(userId, deviceInstallationId())
  }

  override suspend fun getLocalAlbumBackupProgress(
    userId: String,
    albumRef: String,
  ): LocalAlbumBackupProgress = withContext(Dispatchers.IO) {
    coreStage04.getLocalAlbumBackupProgress(userId, deviceInstallationId(), albumRef)
  }

  override fun startBackupChangeObservation(userId: String) {
    libraryChangeObserver?.close()
    libraryChangeObserver = mediaSource.observeLibraryChanges {
      libraryChangeJob?.cancel()
      libraryChangeJob = runtimeScope.launch {
        delay(1_000)
        coreStage04.notifyLibraryChanged(userId, deviceInstallationId())
        val settings = coreStage02.getBackupSettings(userId, deviceInstallationId())
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
      coreAccount.signOut()
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

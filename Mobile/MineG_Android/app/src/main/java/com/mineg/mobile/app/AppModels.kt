package com.mineg.mobile.app

sealed interface AppRoute {
  data object Login : AppRoute
  data object SignUp : AppRoute
  data object ReviewPending : AppRoute
  data class Legal(val document: LegalDocument) : AppRoute
  data object Permission : AppRoute

  data object PrivateSpace : AppRoute
  data object Backup : AppRoute
  data object Profile : AppRoute

  data class PrivateMediaDetail(val mediaId: String) : AppRoute
  data class FamilyMediaDetail(val mediaId: String) : AppRoute
  data object SharedByMe : AppRoute
  data class LocalAlbum(val albumId: String) : AppRoute
  data object BackupSettings : AppRoute
  data object ProfileEdit : AppRoute
  data object RecycleBin : AppRoute
  data object HelpFeedback : AppRoute
}

enum class LegalDocument { TERMS, PRIVACY }

enum class MainTab(val label: String) {
  PRIVATE_SPACE("私人空间"),
  BACKUP("备份"),
  PROFILE("我的"),
}

fun MainTab.route(): AppRoute = when (this) {
  MainTab.PRIVATE_SPACE -> AppRoute.PrivateSpace
  MainTab.BACKUP -> AppRoute.Backup
  MainTab.PROFILE -> AppRoute.Profile
}

enum class PageLoadState { LOADING, CONTENT, EMPTY, ERROR }
enum class MediaKind { PHOTO, VIDEO, GIF, LIVE_PHOTO }
enum class LibraryTab { PRIVATE, SHARED }
enum class LibraryAccess { NOT_DETERMINED, FULL, LIMITED, RESTRICTED, DENIED, SYSTEM_RESTRICTED }

enum class BackupStatus {
  PERMISSION_REQUIRED,
  SCANNING,
  UPLOADING,
  WAITING_WIFI,
  NETWORK_OFFLINE,
  DEVICE_STORAGE_FULL,
  CLOUD_STORAGE_FULL,
  SERVICE_UNAVAILABLE,
  COMPLETE,
  PAUSED,
}

enum class MediaActionState {
  IDLE,
  DOWNLOADING,
  SAVED,
  SAVE_FAILED,
  SHARED,
}

enum class FeedbackCategory(val label: String) {
  BACKUP("备份问题"),
  MEDIA("照片与视频"),
  ACCOUNT("账号问题"),
  SUGGESTION("产品建议"),
}

data class UserProfile(
  val id: String,
  val nickname: String,
  val maskedPhone: String,
  val avatarLabel: String,
  val avatarUrl: String? = null,
)

data class MediaItem(
  val id: String,
  val title: String,
  val kind: MediaKind,
  val capturedAt: String,
  val dateGroup: String,
  val duration: String? = null,
  val sizeLabel: String,
  val owner: UserProfile,
  val sharedByMe: Boolean = false,
  val isShared: Boolean = false,
  val colorSeed: Int,
  val imageUrl: String? = null,
)

data class LocalAlbum(
  val id: String,
  val name: String,
  val mediaCount: Int,
  val mediaIds: List<String>,
  val coverUrls: List<String> = emptyList(),
)

data class DeletedMedia(
  val media: MediaItem,
  val deletedAgo: String,
)

data class AuthUiState(
  val phone: String = "13800138000",
  val password: String = "mineg2026",
  val passwordConfirmation: String = "mineg2026",
  val agreementAccepted: Boolean = true,
  val loading: Boolean = false,
  val reviewSyncing: Boolean = false,
  val fieldErrors: Map<String, String> = emptyMap(),
  val message: String? = null,
)

data class PrivateSpaceUiState(
  val loadState: PageLoadState = PageLoadState.CONTENT,
  val items: List<MediaItem> = emptyList(),
  val errorMessage: String? = null,
)

data class FamilyAlbumUiState(
  val loadState: PageLoadState = PageLoadState.CONTENT,
  val items: List<MediaItem> = emptyList(),
  val errorMessage: String? = null,
)

data class BackupUiState(
  val loadState: PageLoadState = PageLoadState.CONTENT,
  val status: BackupStatus = BackupStatus.UPLOADING,
  val progress: Float = 0.68f,
  val indexedCount: Int = 1_284,
  val totalCount: Int = 1_536,
  val autoBackupEnabled: Boolean = true,
  val allowCellularBackup: Boolean = false,
  val currentMediaTitle: String = "周末野餐.mov",
  val albums: List<LocalAlbum> = emptyList(),
)

data class RecycleBinUiState(
  val loadState: PageLoadState = PageLoadState.CONTENT,
  val items: List<DeletedMedia> = emptyList(),
)

data class FeedbackUiState(
  val category: FeedbackCategory = FeedbackCategory.BACKUP,
  val description: String = "",
  val contact: String = "",
  val submitting: Boolean = false,
  val submitted: Boolean = false,
  val errorMessage: String? = null,
)

sealed interface AppDialog {
  data class DeleteMedia(val mediaId: String) : AppDialog
  data class RestoreMedia(val mediaId: String) : AppDialog
  data object Logout : AppDialog
}

data class MineGAppState(
  val currentRoute: AppRoute = AppRoute.PrivateSpace,
  val backStack: List<AppRoute> = emptyList(),
  val selectedTab: MainTab = MainTab.PRIVATE_SPACE,
  val selectedLibraryTab: LibraryTab = LibraryTab.PRIVATE,
  val auth: AuthUiState = AuthUiState(),
  val profile: UserProfile,
  val libraryAccess: LibraryAccess = LibraryAccess.FULL,
  val privateSpace: PrivateSpaceUiState,
  val familyAlbum: FamilyAlbumUiState,
  val backup: BackupUiState,
  val recycleBin: RecycleBinUiState,
  val feedback: FeedbackUiState = FeedbackUiState(),
  val selectedMediaAction: MediaActionState = MediaActionState.IDLE,
  val dialog: AppDialog? = null,
  val debugPanelVisible: Boolean = false,
) {
  val isMainDestination: Boolean
    get() = currentRoute in setOf(
      AppRoute.PrivateSpace,
      AppRoute.Backup,
      AppRoute.Profile,
    )
}

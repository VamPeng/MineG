package com.mineg.mobile.app

object MockMineGRepository {
  val me = UserProfile("member-me", "林深时见鹿", "138****8888", "林", MockVisualAssets.profileAvatarUrl)
  private val father = UserProfile("member-father", "晨曦爸爸", "", "晨")
  private val mother = UserProfile("member-mother", "妈妈", "", "妈")

  val privateMedia = listOf(
    MediaItem("media-01", "夏日海边", MediaKind.PHOTO, "2026年7月27日 09:42", "今天", sizeLabel = "5.8 MB", owner = me, isShared = true, sharedByMe = true, colorSeed = 0),
    MediaItem("media-02", "周末野餐", MediaKind.VIDEO, "2026年7月27日 08:16", "今天", "0:15", "42.6 MB", me, true, true, 1),
    MediaItem("media-03", "生日蜡烛", MediaKind.LIVE_PHOTO, "2026年7月26日 20:08", "昨天", sizeLabel = "9.2 MB", owner = me, colorSeed = 2),
    MediaItem("media-04", "山间日落", MediaKind.PHOTO, "2026年7月26日 18:32", "昨天", sizeLabel = "7.1 MB", owner = me, colorSeed = 3),
    MediaItem("media-05", "旧相册", MediaKind.PHOTO, "2026年7月25日 14:20", "7月25日", sizeLabel = "4.9 MB", owner = me, colorSeed = 4),
    MediaItem("media-06", "窗边阅读", MediaKind.GIF, "2026年7月25日 10:05", "7月25日", sizeLabel = "12.4 MB", owner = me, colorSeed = 5),
    MediaItem("media-07", "花与清晨", MediaKind.PHOTO, "2026年7月24日 07:48", "7月24日", sizeLabel = "6.3 MB", owner = me, colorSeed = 6),
    MediaItem("media-08", "暮色单车", MediaKind.PHOTO, "2026年7月23日 19:03", "7月23日", sizeLabel = "5.1 MB", owner = me, colorSeed = 7),
    MediaItem("media-09", "一起烘焙", MediaKind.VIDEO, "2026年7月22日 16:11", "7月22日", "1:02", "86.2 MB", me, colorSeed = 8),
  ).mapIndexed { index, media -> media.copy(imageUrl = MockVisualAssets.media[index % MockVisualAssets.media.size]) }

  val familyMedia = listOf(
    privateMedia[0],
    privateMedia[1],
    MediaItem("family-01", "第一次露营", MediaKind.PHOTO, "2026年7月27日 07:12", "今天", sizeLabel = "6.7 MB", owner = father, colorSeed = 9),
    MediaItem("family-02", "花园里的风", MediaKind.LIVE_PHOTO, "2026年7月27日 06:50", "今天", sizeLabel = "8.4 MB", owner = mother, colorSeed = 10),
    MediaItem("family-03", "晚餐时间", MediaKind.GIF, "2026年7月27日 05:30", "今天", sizeLabel = "11.8 MB", owner = mother, colorSeed = 11),
    MediaItem("family-04", "回家的路", MediaKind.VIDEO, "2026年7月27日 04:26", "今天", "1:02", "63.5 MB", father, colorSeed = 12),
    privateMedia[2].copy(id = "family-05", dateGroup = "昨天", owner = mother, sharedByMe = false),
    privateMedia[3].copy(id = "family-06", dateGroup = "昨天", owner = father, sharedByMe = false),
    privateMedia[4].copy(id = "family-07", dateGroup = "昨天", owner = mother, sharedByMe = false),
  ).mapIndexed { index, media ->
    if (media.imageUrl != null) media else media.copy(imageUrl = MockVisualAssets.media[(index + 3) % MockVisualAssets.media.size])
  }

  val albums = listOf(
    LocalAlbum("album-camera", "最近项目", 1_286, privateMedia.take(6).map(MediaItem::id), MockVisualAssets.media.take(6)),
    LocalAlbum("album-family", "家庭时光", 238, privateMedia.drop(2).map(MediaItem::id), MockVisualAssets.media.drop(2).take(6)),
    LocalAlbum("album-screenshots", "旅行", 96, privateMedia.takeLast(4).map(MediaItem::id), MockVisualAssets.media.reversed().take(6)),
  )

  val deletedMedia = privateMedia.takeLast(4).mapIndexed { index, media ->
    DeletedMedia(media, listOf("3天前", "5天前", "12天前", "21天前")[index])
  }

  fun initialState(): MineGAppState = MineGAppState(
    profile = me,
    privateSpace = PrivateSpaceUiState(items = privateMedia),
    familyAlbum = FamilyAlbumUiState(items = familyMedia),
    backup = BackupUiState(albums = albums),
    recycleBin = RecycleBinUiState(items = deletedMedia),
  )
}

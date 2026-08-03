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
    MediaItem("family-01", "积木时光", MediaKind.PHOTO, "2024年10月20日", "今天", sizeLabel = "5.8 MB", owner = me, sharedByMe = true, colorSeed = 0, imageUrl = MockVisualAssets.familyTimelineMedia[0], detailImageUrl = MockVisualAssets.familyDetailMedia),
    MediaItem("family-02", "草莓蛋糕", MediaKind.LIVE_PHOTO, "2024年10月20日", "今天", sizeLabel = "8.4 MB", owner = mother, colorSeed = 1, imageUrl = MockVisualAssets.familyTimelineMedia[1]),
    MediaItem("family-03", "公园野餐", MediaKind.PHOTO, "2024年10月20日", "今天", sizeLabel = "6.7 MB", owner = me, sharedByMe = true, colorSeed = 2, imageUrl = MockVisualAssets.familyTimelineMedia[2]),
    MediaItem("family-04", "落叶小狗", MediaKind.GIF, "2024年10月20日", "今天", sizeLabel = "11.8 MB", owner = mother, colorSeed = 3, imageUrl = MockVisualAssets.familyTimelineMedia[3]),
    MediaItem("family-05", "家的角落", MediaKind.PHOTO, "2024年10月20日", "今天", sizeLabel = "4.9 MB", owner = mother, colorSeed = 4, imageUrl = MockVisualAssets.familyTimelineMedia[4]),
    MediaItem("family-06", "傍晚笑声", MediaKind.VIDEO, "2024年10月20日", "今天", "1:02", "63.5 MB", me, sharedByMe = true, colorSeed = 5, imageUrl = MockVisualAssets.familyTimelineMedia[5]),
    MediaItem("family-07", "晨光湖畔", MediaKind.PHOTO, "2024年10月19日", "昨天", sizeLabel = "6.3 MB", owner = father, colorSeed = 6, imageUrl = MockVisualAssets.familyTimelineMedia[6]),
    MediaItem("family-08", "小小鞋子", MediaKind.PHOTO, "2024年10月19日", "昨天", sizeLabel = "5.1 MB", owner = me, sharedByMe = true, colorSeed = 7, imageUrl = MockVisualAssets.familyTimelineMedia[7]),
    MediaItem("family-09", "窗边共读", MediaKind.LIVE_PHOTO, "2024年10月19日", "昨天", sizeLabel = "9.2 MB", owner = mother, colorSeed = 8, imageUrl = MockVisualAssets.familyTimelineMedia[8]),
  )

  val albums = listOf(
    LocalAlbum("album-camera", "最近项目", 1_286, privateMedia.take(6).map(MediaItem::id), MockVisualAssets.backupMedia.take(6)),
    LocalAlbum("album-family", "家庭时光", 238, privateMedia.drop(2).map(MediaItem::id), listOf(
      MockVisualAssets.backupMedia[6], MockVisualAssets.backupMedia[7], MockVisualAssets.backupMedia[0],
      MockVisualAssets.backupMedia[2], MockVisualAssets.backupMedia[5], MockVisualAssets.backupMedia[1],
    )),
    LocalAlbum("album-screenshots", "旅行", 96, privateMedia.takeLast(4).map(MediaItem::id), listOf(
      MockVisualAssets.backupMedia[4], MockVisualAssets.backupMedia[3], MockVisualAssets.backupMedia[5],
      MockVisualAssets.backupMedia[1], MockVisualAssets.backupMedia[0], MockVisualAssets.backupMedia[7],
    )),
  )

  val deletedMedia = MockVisualAssets.recycleMedia.mapIndexed { index, imageUrl ->
    DeletedMedia(
      media = privateMedia[index % privateMedia.size].copy(
        id = "recycle-${index + 1}",
        imageUrl = imageUrl,
        detailImageUrl = MockVisualAssets.recycleRestoreMedia,
      ),
      deletedAgo = listOf("3天前", "5天前", "12天前", "15天前", "21天前", "28天前")[index],
    )
  }

  fun initialState(): MineGAppState = MineGAppState(
    profile = me,
    privateSpace = PrivateSpaceUiState(items = privateMedia),
    familyAlbum = FamilyAlbumUiState(items = familyMedia),
    backup = BackupUiState(albums = albums),
    recycleBin = RecycleBinUiState(items = deletedMedia),
  )
}

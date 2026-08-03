package com.mineg.mobile.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryPagesTest {
  @Test
  fun `cloud media item maps request and decode progress to loading style`() {
    assertEquals(
      MediaItemVisualState.LOADING,
      mediaItemVisualState(
        imageUrl = null,
        canLoadRemotePreview = true,
        previewLoading = true,
        previewUnavailable = false,
        imageState = MediaItemImageState.LOADING,
      ),
    )
    assertEquals(
      MediaItemVisualState.LOADING,
      mediaItemVisualState(
        imageUrl = "file:///verified/media-1",
        canLoadRemotePreview = true,
        previewLoading = false,
        previewUnavailable = false,
        imageState = MediaItemImageState.LOADING,
      ),
    )
  }

  @Test
  fun `cloud media item maps access or decode failure to retry style`() {
    assertEquals(
      MediaItemVisualState.FAILED,
      mediaItemVisualState(
        imageUrl = null,
        canLoadRemotePreview = true,
        previewLoading = false,
        previewUnavailable = true,
        imageState = MediaItemImageState.LOADING,
      ),
    )
    assertEquals(
      MediaItemVisualState.FAILED,
      mediaItemVisualState(
        imageUrl = "file:///verified/media-1",
        canLoadRemotePreview = true,
        previewLoading = false,
        previewUnavailable = false,
        imageState = MediaItemImageState.FAILED,
      ),
    )
  }

  @Test
  fun `cloud media item uses success style only after image decode succeeds`() {
    assertEquals(
      MediaItemVisualState.SUCCESS,
      mediaItemVisualState(
        imageUrl = "file:///verified/media-1",
        canLoadRemotePreview = true,
        previewLoading = false,
        previewUnavailable = false,
        imageState = MediaItemImageState.SUCCESS,
      ),
    )
  }

  @Test
  fun `paging label includes the number of loaded media while more data is available`() {
    assertEquals("加载更多（已加载50）", mediaPagingLabel(itemCount = 50, fullyLoaded = false))
  }

  @Test
  fun `paging label reports completion after all media is loaded`() {
    assertEquals("已加载全部媒体", mediaPagingLabel(itemCount = 1_548, fullyLoaded = true))
  }

  @Test
  fun `visible paging footer automatically loads only while more data is available`() {
    assertTrue(shouldAutoLoadMedia(footerVisible = true, loadingMore = false, fullyLoaded = false))
    assertFalse(shouldAutoLoadMedia(footerVisible = false, loadingMore = false, fullyLoaded = false))
    assertFalse(shouldAutoLoadMedia(footerVisible = true, loadingMore = true, fullyLoaded = false))
    assertFalse(shouldAutoLoadMedia(footerVisible = true, loadingMore = false, fullyLoaded = true))
  }

  @Test
  fun `preview window keeps visible media and up to two rows ahead`() {
    val ids = List(60) { "media-$it" }

    assertEquals(
      (9..28).map { "media-$it" },
      privateMediaPreviewWindow(ids, visibleIndices = (12..22).toList()),
    )
    assertEquals(
      (0..7).map { "media-$it" },
      privateMediaPreviewWindow(ids, visibleIndices = (0..3).toList()),
    )
    assertTrue(privateMediaPreviewWindow(ids, visibleIndices = listOf(60)).isEmpty())
  }

  @Test
  fun `private detail prefers a mapped local original over the cloud thumbnail`() {
    val media = MediaItem(
      id = "media-1",
      title = "照片",
      kind = MediaKind.PHOTO,
      capturedAt = "2026年8月3日 12:00",
      dateGroup = "8月3日",
      sizeLabel = "1.0 MB",
      owner = UserProfile("user-1", "用户", "138****8000", "用"),
      colorSeed = 1,
      imageUrl = "file:///verified/cloud-thumbnail",
      detailImageUrl = "content://media/external/file/123",
      canLoadRemotePreview = true,
    )

    assertEquals("content://media/external/file/123", detailArtworkSource(media))
    assertEquals(
      "file:///verified/cloud-thumbnail",
      detailArtworkSource(media.copy(detailImageUrl = null)),
    )
  }
}

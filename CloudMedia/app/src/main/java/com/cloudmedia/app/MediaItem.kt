package com.cloudmedia.app

import android.net.Uri

sealed class GalleryRow

data class DateHeader(val label: String) : GalleryRow()

data class UploadResult(val ok: Boolean, val detail: String)

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val size: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
    var selected: Boolean = false
) : GalleryRow()

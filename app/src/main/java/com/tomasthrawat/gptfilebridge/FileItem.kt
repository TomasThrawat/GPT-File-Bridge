package com.tomasthrawat.gptfilebridge

import android.net.Uri

data class FileItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val id: String = uri.toString()
)

data class UploadResult(
    val file: FileItem,
    val objectPath: String,
    val publicUrl: String,
    val prompt: String
)

package com.tomasthrawat.gptfilebridge

import android.content.ContentResolver
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

class SupabaseTusUploader(
    private val contentResolver: ContentResolver
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun upload(
        file: FileItem,
        onProgress: suspend (sent: Long, total: Long) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {
        require(file.sizeBytes > 0) { "File size is unavailable: " + file.name }

        val objectPath = "client/" + UUID.randomUUID().toString() + "-" + sanitize(file.name)
        val uploadUrl = createTusUploadUrl(objectPath, file)
        var offset = 0L
        val total = file.sizeBytes

        contentResolver.openInputStream(file.uri)?.use { source ->
            val input = BufferedInputStream(source, CHUNK_SIZE.toInt())
            while (offset < total) {
                val remaining = total - offset
                val length = minOf(CHUNK_SIZE, remaining)
                offset = patchChunk(uploadUrl, input, length, offset)
                onProgress(offset, total)
            }
        } ?: error("Could not open " + file.name)

        val encodedPath = objectPath.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")
        }
        val publicUrl = SupabaseConfig.STORAGE_HOST +
            "/storage/v1/object/public/" + SupabaseConfig.BUCKET + "/" + encodedPath

        val prompt = "Please access this file from the public URL below and work with its contents.\n" +
            "Filename: " + file.name + "\n" +
            "MIME type: " + file.mimeType + "\n" +
            "Size: " + file.sizeBytes + " bytes\n" +
            "URL: " + publicUrl + "\n" +
            "This cloud URL was created because direct ChatGPT attachment upload was unavailable."

        UploadResult(file, objectPath, publicUrl, prompt)
    }

    private fun createTusUploadUrl(path: String, file: FileItem): String {
        val metadata =
            "bucketName " + base64(SupabaseConfig.BUCKET) +
            ",objectName " + base64(path) +
            ",contentType " + base64(file.mimeType.ifBlank { "application/octet-stream" }) +
            ",cacheControl " + base64("3600")

        val request = Request.Builder()
            .url(SupabaseConfig.STORAGE_HOST + "/storage/v1/upload/resumable")
            .post(RequestBody.create(null, ByteArray(0)))
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Length", file.sizeBytes.toString())
            .header("Upload-Metadata", metadata)
            .header("X-Upsert", "false")
            .header("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            .header("Authorization", "Bearer " + SupabaseConfig.PUBLISHABLE_KEY)
            .build()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { errorMessage("Create upload", response) }
            val location = response.header("Location")
                ?: error("Supabase did not return a TUS upload URL")
            return if (location.startsWith("http")) {
                location
            } else {
                SupabaseConfig.STORAGE_HOST + location
            }
        }
    }

    private fun patchChunk(
        url: String,
        input: InputStream,
        length: Long,
        offset: Long
    ): Long {
        val body = object : RequestBody() {
            override fun contentType() = "application/offset+octet-stream".toMediaType()
            override fun contentLength() = length

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(IO_BUFFER_SIZE)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) error("Unexpected end of file")
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }

        val request = Request.Builder()
            .url(url)
            .patch(body)
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Offset", offset.toString())
            .header("Content-Type", "application/offset+octet-stream")
            .header("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            .header("Authorization", "Bearer " + SupabaseConfig.PUBLISHABLE_KEY)
            .build()

        client.newCall(request).execute().use { response ->
            check(response.code == 204) { errorMessage("Upload chunk", response) }
            return response.header("Upload-Offset")?.toLongOrNull() ?: (offset + length)
        }
    }

    private fun errorMessage(action: String, response: Response): String {
        return action + " failed (" + response.code + "): " + response.body.string().take(500)
    }

    private fun sanitize(name: String): String {
        val out = name.map {
            when {
                it.isLetterOrDigit() -> it
                it in "._-()+?;&=,@! '" -> it
                else -> '_'
            }
        }.joinToString("")
        return out.take(180).ifBlank { "file" }
    }

    private fun base64(value: String): String =
        android.util.Base64.encodeToString(value.toByteArray(), android.util.Base64.NO_WRAP)

    companion object {
        const val CHUNK_SIZE = 6L * 1024L * 1024L
        const val IO_BUFFER_SIZE = 64 * 1024
    }
}

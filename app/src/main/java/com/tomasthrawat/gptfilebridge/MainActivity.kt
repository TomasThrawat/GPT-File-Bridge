package com.tomasthrawat.gptfilebridge

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.lazy.LazyColumn
import androidx.compose.foundation.layout.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val selected = remember { mutableStateListOf<FileItem>() }
        val results = remember { mutableStateListOf<UploadResult>() }
        var uploading by remember { mutableStateOf(false) }
        var currentName by remember { mutableStateOf("") }
        var progress by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                queryFile(uri)?.let { item ->
                    if (selected.none { it.id == item.id }) selected.add(item)
                }
            }
        }

        MaterialTheme {
            Scaffold(
                topBar = { TopAppBar(title = { Text("GPT File Bridge") }) }
            ) { padding ->
                Surface(Modifier.fillMaxSize().padding(padding)) {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Send TXT, photos, videos, ZIP, PDF, APK and other files through a cloud URL when direct ChatGPT attachments are unavailable.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Button(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = !uploading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Choose files")
                        }

                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(selected, key = { it.id }) { item ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                formatBytes(item.sizeBytes) + " • " + item.mimeType,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { selected.remove(item) },
                                            enabled = !uploading
                                        ) {
                                            Text("Remove")
                                        }
                                    }
                                }
                            }
                        }

                        if (uploading) {
                            Text("Uploading " + currentName)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    uploading = true
                                    results.clear()
                                    val uploader = SupabaseTusUploader(contentResolver)

                                    for (file in selected.toList()) {
                                        currentName = file.name
                                        progress = 0f
                                        runCatching {
                                            uploader.upload(file) { sent, total ->
                                                progress = if (total > 0) {
                                                    sent.toFloat() / total.toFloat()
                                                } else {
                                                    0f
                                                }
                                            }
                                        }.onSuccess {
                                            results.add(it)
                                        }.onFailure {
                                            Toast.makeText(
                                                this@MainActivity,
                                                file.name + ": " + it.message,
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }

                                    uploading = false
                                    currentName = ""
                                    progress = 0f
                                    selected.clear()
                                }
                            },
                            enabled = selected.isNotEmpty() && !uploading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Upload to GPT bridge")
                        }

                        if (results.isNotEmpty()) {
                            Text("Ready to share", style = MaterialTheme.typography.titleMedium)
                            LazyColumn(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(results, key = { it.objectPath }) { result ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(
                                            Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                result.file.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                result.publicUrl,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    onClick = { shareText(result.prompt) }
                                                ) {
                                                    Text("Share to ChatGPT")
                                                }
                                                OutlinedButton(
                                                    onClick = { copyToClipboard(result.prompt) }
                                                ) {
                                                    Text("Copy prompt")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (selected.isEmpty()) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                "No files selected",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    private fun queryFile(uri: Uri): FileItem? {
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = 0L

        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }

        return FileItem(uri, name, mime, size)
    }

    private fun shareText(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Share bridge link"
            )
        )
    }

    private fun copyToClipboard(text: String) {
        val clipboard =
            getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("GPT File Bridge", text)
        )
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "Unknown size"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return "%.1f %s".format(value, units[index])
    }
}

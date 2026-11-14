package com.littlebird.sharing

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var server: LocalFileServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isServerRunning by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Server is stopped")
    private var hostAddresses by mutableStateOf(emptyList<String>())
    private var selectedQrAddress by mutableStateOf<String?>(null)
    private var sharedFiles by mutableStateOf<List<SharedFile>>(emptyList())
    private var receivedFiles by mutableStateOf<List<ReceivedFile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val filePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris ->
                val updated = sharedFiles.toMutableList()
                uris.forEach { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                        // Some document providers do not offer persistable permissions.
                    }
                    if (updated.none { it.uri == uri }) {
                        updated.add(readSharedFile(uri))
                    }
                }
                sharedFiles = updated
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Little Bird Sharing", style = MaterialTheme.typography.headlineMedium)
                        Text("Turn this phone into a file-sharing host.", style = MaterialTheme.typography.bodyLarge)

                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("HOST SERVER", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    if (isServerRunning) "Running" else "Stopped",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = if (isServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(statusMessage)
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { startServer() }, enabled = !isServerRunning, modifier = Modifier.weight(1f)) {
                                Text("Start server")
                            }
                            OutlinedButton(onClick = { stopServer() }, enabled = isServerRunning, modifier = Modifier.weight(1f)) {
                                Text("Stop server")
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("SHARED FILES", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    if (sharedFiles.isEmpty()) "No files selected yet." else "${sharedFiles.size} file(s) ready to share.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Button(
                                    onClick = { filePicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Add files")
                                }
                                if (sharedFiles.isNotEmpty()) {
                                    sharedFiles.forEach { file ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(formatFileSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    TextButton(onClick = { sharedFiles = emptyList() }) {
                                        Text("Remove all shared files")
                                    }
                                }
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("RECEIVED FILES", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    if (receivedFiles.isEmpty()) "No files received yet." else "${receivedFiles.size} file(s) received on this phone.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                receivedFiles.takeLast(10).reversed().forEach { file ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(formatFileSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (receivedFiles.size > 10) {
                                    Text("Showing the 10 most recent uploads.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        if (isServerRunning) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("OPEN ON ANOTHER DEVICE", style = MaterialTheme.typography.labelLarge)
                                    if (hostAddresses.isEmpty()) {
                                        Text("No local IPv4 address found. Check the Wi-Fi connection.")
                                    } else {
                                        hostAddresses.forEach { address ->
                                            val url = "http://" + address + ":" + LocalFileServer.DEFAULT_PORT
                                            Text(url, style = MaterialTheme.typography.titleMedium)
                                            OutlinedButton(onClick = { selectedQrAddress = url }) {
                                                Text("Show QR code")
                                            }
                                        }
                                    }
                                    Text("Connect both devices to the same Wi-Fi network.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Text("Stage 3: upload to this phone", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Connected devices can now select files on the webpage and upload them to this phone. Received files are stored in the app private Downloads/Received folder.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text("Stage 2: browse and download", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Select files above. Devices connected to this phone can open its address to view and download the shared files. The webpage refreshes its file list automatically.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            selectedQrAddress?.let { url ->
                val qrBitmap = remember(url) { createQrBitmap(url) }
                AlertDialog(
                    onDismissRequest = { selectedQrAddress = null },
                    title = { Text("Scan to connect") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Scan this QR code with the other device's camera.")
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR code for " + url,
                                modifier = Modifier.fillMaxWidth().height(260.dp),
                                contentScale = ContentScale.Fit
                            )
                            Text(url, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { selectedQrAddress = null }) { Text("Close") }
                    }
                )
            }
        }
    }

    private fun readSharedFile(uri: android.net.Uri): SharedFile {
        var name = "Shared file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        return SharedFile(uri, name, size, mimeType)
    }

    private fun formatFileSize(size: Long): String {
        if (size < 0) return "Size unavailable"
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    private fun createQrBitmap(content: String): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640)
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        for (x in 0 until 640) {
            for (y in 0 until 640) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }

    private fun startServer() {
        statusMessage = "Starting server…"
        thread(name = "sharing-server-start") {
            try {
                val newServer = LocalFileServer(this, { sharedFiles }, { received ->
                    mainHandler.post { receivedFiles = receivedFiles + received }
                })
                newServer.start(SOCKET_TIMEOUT, false)
                server = newServer
                val addresses = getLocalIpv4Addresses()
                mainHandler.post {
                    hostAddresses = addresses
                    isServerRunning = true
                    statusMessage = "Listening on port " + LocalFileServer.DEFAULT_PORT
                }
            } catch (error: Exception) {
                mainHandler.post {
                    isServerRunning = false
                    statusMessage = "Could not start server: " + (error.localizedMessage ?: "Unknown error")
                }
            }
        }
    }

    private fun stopServer() {
        thread(name = "sharing-server-stop") {
            server?.stop()
            server = null
            mainHandler.post {
                isServerRunning = false
                hostAddresses = emptyList()
                statusMessage = "Server is stopped"
            }
        }
    }

    private fun getLocalIpv4Addresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { network ->
                    network.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .mapNotNull { it.hostAddress }
                }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        private const val SOCKET_TIMEOUT = 5_000
    }
}

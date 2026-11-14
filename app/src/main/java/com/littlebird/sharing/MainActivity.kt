package com.littlebird.sharing

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.ContentUris
import androidx.core.content.FileProvider
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
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
import androidx.compose.material3.OutlinedTextField
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.Inet4Address
import java.net.NetworkInterface
import java.io.File
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
    private var accessPin by mutableStateOf("")
    private var uploadSpeed by mutableStateOf("0 B/s")
    private var downloadSpeed by mutableStateOf("0 B/s")
    private var connectedClients by mutableStateOf<List<ConnectedClient>>(emptyList())
    private var transferHistory by mutableStateOf<List<TransferHistoryItem>>(emptyList())

    private val speedUpdater = object : Runnable {
        override fun run() {
            server?.getTransferSpeeds()?.let { speeds ->
                uploadSpeed = formatTransferRate(speeds.uploadBytesPerSecond)
                downloadSpeed = formatTransferRate(speeds.downloadBytesPerSecond)
            }
            connectedClients = server?.getConnectedClients().orEmpty()
            if (isServerRunning) mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadReceivedFiles()
        loadTransferHistory()
        accessPin = getSharedPreferences("sharing_security", MODE_PRIVATE).getString("access_pin", "").orEmpty()
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


                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("CONNECTION SECURITY", style = MaterialTheme.typography.labelLarge)
                                OutlinedTextField(
                                    value = accessPin,
                                    onValueChange = { value -> accessPin = value.filter { it.isDigit() }.take(12) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isServerRunning,
                                    label = { Text("Host PIN") },
                                    placeholder = { Text("Set a 4–12 digit PIN") },
                                    supportingText = { Text(if (isServerRunning) "Stop the server before changing its PIN." else "Connected devices must enter this PIN before accessing files.") },
                                    singleLine = true
                                )
                            }
                        }


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


                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("LIVE TRANSFER SPEED", style = MaterialTheme.typography.labelLarge)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Download to phone", style = MaterialTheme.typography.bodySmall)
                                        Text(uploadSpeed, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text("Upload from phone", style = MaterialTheme.typography.bodySmall)
                                        Text(downloadSpeed, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { startServer() }, enabled = !isServerRunning && accessPin.length in 4..12, modifier = Modifier.weight(1f)) {
                                Text("Start server")
                            }
                            OutlinedButton(onClick = { stopServer() }, enabled = isServerRunning, modifier = Modifier.weight(1f)) {
                                Text("Stop server")
                            }
                        }

                        if (isServerRunning) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("CONNECTED DEVICES", style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        if (connectedClients.isEmpty()) "No authenticated devices are active." else
                                            "${connectedClients.size} device(s) currently connected.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    connectedClients.forEach { client ->
                                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(client.ipAddress, style = MaterialTheme.typography.titleSmall)
                                            Text(client.deviceInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                            Text(
                                                "Connected ${formatTimestamp(client.connectedAt)} · Active ${formatTimestamp(client.lastSeen)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Text(
                                        "Device details are based on its network address and browser information. Devices inactive for 30 seconds disappear from this list.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(formatFileSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        OutlinedButton(onClick = { openReceivedFile(file) }) {
                                            Text("Open")
                                        }
                                    }
                                }
                                if (receivedFiles.size > 10) {
                                    Text("Showing the 10 most recent uploads.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("TRANSFER HISTORY", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    if (transferHistory.isEmpty()) "Completed transfers will appear here." else
                                        "${transferHistory.size} recent transfer(s).",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                transferHistory.take(20).forEach { item ->
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${item.direction} · ${formatFileSize(item.size)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "${item.clientIp} · ${formatTimestamp(item.timestamp)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            item.deviceInfo,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
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
                            "Connected devices can now select files on the webpage and upload them to this phone. Received files are saved to the public Downloads/Little Bird Sharing folder.",
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

    private fun loadReceivedFiles() {
        receivedFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val files = mutableListOf<ReceivedFile>()
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE
            )
            contentResolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(android.os.Environment.DIRECTORY_DOWNLOADS + "/Little Bird Sharing/"),
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    files.add(
                        ReceivedFile(
                            cursor.getString(nameColumn) ?: "Received file",
                            if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn),
                            uri.toString()
                        )
                    )
                }
            }
            files.reversed()
        } else {
            val directory = File(
                getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
                "Received"
            )
            directory.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.lastModified() }
                ?.map { file -> ReceivedFile(file.name, file.length(), file.absolutePath) }
                ?: emptyList()
        }
    }

    private fun loadTransferHistory() {
        val stored = getSharedPreferences("sharing_history", MODE_PRIVATE)
            .getString("transfer_history", "[]") ?: "[]"
        transferHistory = try {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                TransferHistoryItem(
                    name = item.optString("name", "Unknown file"),
                    size = item.optLong("size", 0L),
                    direction = item.optString("direction", "Transfer"),
                    clientIp = item.optString("clientIp", "Unknown IP"),
                    deviceInfo = item.optString("deviceInfo", "Unknown device"),
                    timestamp = item.optLong("timestamp", 0L),
                    success = item.optBoolean("success", true)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun addTransferHistory(item: TransferHistoryItem) {
        transferHistory = (listOf(item) + transferHistory).take(100)
        val array = JSONArray()
        transferHistory.forEach { entry ->
            array.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("size", entry.size)
                    .put("direction", entry.direction)
                    .put("clientIp", entry.clientIp)
                    .put("deviceInfo", entry.deviceInfo)
                    .put("timestamp", entry.timestamp)
                    .put("success", entry.success)
            )
        }
        getSharedPreferences("sharing_history", MODE_PRIVATE)
            .edit().putString("transfer_history", array.toString()).apply()
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "Unknown time"
        return SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun openReceivedFile(received: ReceivedFile) {
        try {
            val contentUri: Uri
            val mimeType: String
            if (received.path.startsWith("content://")) {
                contentUri = Uri.parse(received.path)
                mimeType = contentResolver.getType(contentUri) ?: "*/*"
            } else {
                val file = File(received.path)
                if (!file.exists()) {
                    statusMessage = "File not found: ${received.name}"
                    return
                }
                contentUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.provider",
                    file
                )
                mimeType = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open file"))
        } catch (error: Exception) {
            statusMessage = "Unable to open file: ${error.localizedMessage ?: "No compatible app found"}"
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

    private fun formatTransferRate(bytes: Long): String {
        if (bytes < 1024) return "$bytes B/s"
        if (bytes < 1024 * 1024) return String.format("%.1f KB/s", bytes / 1024.0)
        return String.format("%.2f MB/s", bytes / (1024.0 * 1024.0))
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
        if (accessPin.length !in 4..12) {
            statusMessage = "Set a PIN containing 4 to 12 digits before starting the server."
            return
        }
        getSharedPreferences("sharing_security", MODE_PRIVATE)
            .edit().putString("access_pin", accessPin).apply()
        statusMessage = "Starting secure server…"
        thread(name = "sharing-server-start") {
            try {
                val newServer = LocalFileServer(
                    this,
                    { sharedFiles },
                    { received -> mainHandler.post { receivedFiles = receivedFiles + received } },
                    accessPin,
                    { item -> mainHandler.post { addTransferHistory(item) } }
                )
                newServer.start(SOCKET_TIMEOUT, false)
                server = newServer
                val addresses = getLocalIpv4Addresses()
                mainHandler.post {
                    hostAddresses = addresses
                    isServerRunning = true
                    mainHandler.removeCallbacks(speedUpdater)
                    mainHandler.post(speedUpdater)
                    statusMessage = "PIN-protected server listening on port " + LocalFileServer.DEFAULT_PORT
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
                mainHandler.removeCallbacks(speedUpdater)
                uploadSpeed = "0 B/s"
                downloadSpeed = "0 B/s"
                connectedClients = emptyList()
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
        mainHandler.removeCallbacks(speedUpdater)
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        private const val SOCKET_TIMEOUT = 5_000
    }
}

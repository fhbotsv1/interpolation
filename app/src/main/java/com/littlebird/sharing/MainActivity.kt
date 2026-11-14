package com.littlebird.sharing

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var server: LocalFileServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isServerRunning by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Server is stopped")
    private var hostAddresses by mutableStateOf(emptyList<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
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
                        if (isServerRunning) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("OPEN ON ANOTHER DEVICE", style = MaterialTheme.typography.labelLarge)
                                    if (hostAddresses.isEmpty()) {
                                        Text("No local IPv4 address found. Check the Wi-Fi connection.")
                                    } else {
                                        hostAddresses.forEach { address ->
                                            Text("http://" + address + ":" + LocalFileServer.DEFAULT_PORT, style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                    Text("Connect both devices to the same Wi-Fi network.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Stage 1: server setup", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This milestone verifies that another device can reach the phone. File browsing, downloads, uploads, QR connection and hotspot mode will be added step by step.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    private fun startServer() {
        statusMessage = "Starting server…"
        thread(name = "sharing-server-start") {
            try {
                val newServer = LocalFileServer()
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

    companion object { private const val SOCKET_TIMEOUT = 5_000 }
}

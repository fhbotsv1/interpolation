package com.littlebird.sharing

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock

data class SharedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
)

data class TransferSpeeds(val uploadBytesPerSecond: Long, val downloadBytesPerSecond: Long)

data class ReceivedFile(
    val name: String,
    val size: Long,
    val path: String
)

class LocalFileServer(
    private val context: Context,
    private val getSharedFiles: () -> List<SharedFile>,
    private val onFileReceived: (ReceivedFile) -> Unit = {},
    private val accessPin: String,
    port: Int = DEFAULT_PORT
) : NanoHTTPD("0.0.0.0", port) {

    private val activeTokens = ConcurrentHashMap.newKeySet<String>()
    private val pinHash = sha256(accessPin)
    private val uploadedBytes = AtomicLong(0)
    private val downloadedBytes = AtomicLong(0)
    private var lastSpeedSample = SystemClock.elapsedRealtime()
    private var lastUploadedSample = 0L
    private var lastDownloadedSample = 0L
    @Volatile private var currentSpeeds = TransferSpeeds(0, 0)

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/health" ->
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")

                session.uri == "/auth" && session.method == Method.POST -> serveAuth(session)

                session.uri == "/api/files" -> if (isAuthorized(session)) serveFileList() else unauthorized()

                session.uri == "/api/status" -> if (isAuthorized(session)) serveTransferStatus() else unauthorized()

                session.uri == "/api/upload-progress" -> if (isAuthorized(session)) recordUploadProgress(session) else unauthorized()

                session.uri == "/upload" && session.method == Method.POST -> if (isAuthorized(session)) serveUpload(session) else unauthorized()

                session.uri.startsWith("/download/") -> if (isAuthorized(session)) serveDownload(session.uri) else unauthorized()

                else -> newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_HTML,
                    webPage()
                ).apply {
                    addHeader("Cache-Control", "no-store")
                }
            }
        } catch (error: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                error.localizedMessage ?: "Server error"
            )
        }
    }


    private fun serveAuth(session: IHTTPSession): Response {
        return try {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val suppliedPin = session.parameters["pin"]?.firstOrNull().orEmpty()
            if (suppliedPin.isBlank() || !MessageDigest.isEqual(sha256(suppliedPin), pinHash)) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Incorrect PIN")
            }
            val token = UUID.randomUUID().toString()
            activeTokens.add(token)
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                JSONObject().put("token", token).toString()).apply {
                addHeader("Cache-Control", "no-store")
            }
        } catch (error: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Unable to verify PIN")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val token = session.headers["x-auth-token"] ?: session.parameters["token"]?.firstOrNull()
        return activeTokens.contains(token)
    }

    private fun unauthorized(): Response = newFixedLengthResponse(
        Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "PIN authentication required"
    )

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))


    @Synchronized
    fun getTransferSpeeds(): TransferSpeeds {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastSpeedSample
        if (elapsed >= 900L) {
            val uploaded = uploadedBytes.get()
            val downloaded = downloadedBytes.get()
            currentSpeeds = TransferSpeeds(
                ((uploaded - lastUploadedSample) * 1000L / elapsed).coerceAtLeast(0),
                ((downloaded - lastDownloadedSample) * 1000L / elapsed).coerceAtLeast(0)
            )
            lastUploadedSample = uploaded
            lastDownloadedSample = downloaded
            lastSpeedSample = now
        }
        return currentSpeeds
    }


    private fun recordUploadProgress(session: IHTTPSession): Response {
        val delta = session.parameters["delta"]?.firstOrNull()?.toLongOrNull() ?: 0L
        if (delta > 0L && delta <= 8L * 1024L * 1024L) {
            uploadedBytes.addAndGet(delta)
        }
        return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            .apply { addHeader("Cache-Control", "no-store") }
    }

    private fun serveTransferStatus(): Response {
        val speeds = getTransferSpeeds()
        val json = JSONObject()
            .put("uploadBytesPerSecond", speeds.uploadBytesPerSecond)
            .put("downloadBytesPerSecond", speeds.downloadBytesPerSecond)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString())
            .apply { addHeader("Cache-Control", "no-store") }
    }

    private class CountingInputStream(input: InputStream, private val onBytes: (Long) -> Unit) :
        FilterInputStream(input) {
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) onBytes(1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) onBytes(count.toLong())
            return count
        }
    }

    private fun serveFileList(): Response {
        val filesJson = JSONArray()
        getSharedFiles().forEachIndexed { index, file ->
            filesJson.put(
                JSONObject()
                    .put("id", index)
                    .put("name", file.name)
                    .put("size", file.size)
            )
        }
        val body = JSONObject()
            .put("files", filesJson)
            .toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
            .apply {
                addHeader("Cache-Control", "no-store")
                addHeader("Access-Control-Allow-Origin", "*")
            }
    }

    private fun serveUpload(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (contentLength > MAX_UPLOAD_BYTES) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "File is too large. Maximum upload size is 500 MB."
            )
        }

        return try {
            val tempFiles = HashMap<String, String>()
            session.parseBody(tempFiles)

            val originalName = session.parameters["file"]?.firstOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No file selected")

            val tempPath = tempFiles["file"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Upload data missing")

            val tempFile = File(tempPath)
            if (!tempFile.exists() || !tempFile.isFile) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Upload file not found")
            }
            if (tempFile.length() > MAX_UPLOAD_BYTES) {
                tempFile.delete()
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "File is too large. Maximum upload size is 500 MB."
                )
            }

            val safeName = originalName.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                .trim()
                .take(120)
                .ifBlank { "uploaded_file" }

            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(safeName.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"

            val received = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Little Bird Sharing"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val contentUri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IllegalStateException("Unable to create file in public Downloads")

                try {
                    val output = context.contentResolver.openOutputStream(contentUri)
                        ?: throw IllegalStateException("Unable to write uploaded file")
                    output.use { stream -> tempFile.inputStream().use { it.copyTo(stream) } }
                    context.contentResolver.update(
                        contentUri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                    ReceivedFile(safeName, tempFile.length(), contentUri.toString())
                } catch (error: Exception) {
                    context.contentResolver.delete(contentUri, null, null)
                    throw error
                }
            } else {
                val receivedDirectory = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                    "Received"
                )
                if (!receivedDirectory.exists() && !receivedDirectory.mkdirs()) {
                    throw IllegalStateException("Cannot create received files folder")
                }
                val destination = File(receivedDirectory, "${System.currentTimeMillis()}_$safeName")
                tempFile.copyTo(destination, overwrite = false)
                ReceivedFile(destination.name, destination.length(), destination.absolutePath)
            }
            tempFile.delete()
            onFileReceived(received)

            val result = JSONObject()
                .put("success", true)
                .put("name", received.name)
                .put("size", received.size)
                .toString()
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", result)
        } catch (error: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                error.localizedMessage ?: "Upload failed"
            )
        }
    }

    private fun serveDownload(path: String): Response {
        val id = path.removePrefix("/download/").toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file ID")

        val file = getSharedFiles().getOrNull(id)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        val source: InputStream = context.contentResolver.openInputStream(file.uri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unable to open file")

        val input: InputStream = CountingInputStream(source) { count -> downloadedBytes.addAndGet(count) }
        val response = if (file.size >= 0L) {
            newFixedLengthResponse(
                Response.Status.OK,
                file.mimeType.ifBlank { "application/octet-stream" },
                input,
                file.size
            )
        } else {
            newChunkedResponse(
                Response.Status.OK,
                file.mimeType.ifBlank { "application/octet-stream" },
                input
            )
        }
        val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun webPage(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="theme-color" content="#101827">
          <title>Little Bird Sharing</title>
          <style>
            * { box-sizing: border-box; }
            body { margin: 0; padding: 24px 16px; background: #f3f5f9; color: #182230; font-family: system-ui, sans-serif; }
            main { max-width: 680px; margin: 0 auto; }
            header { padding: 22px; border-radius: 18px; background: #17243a; color: white; }
            h1 { margin: 0 0 6px; font-size: 25px; }
            header p { margin: 0; color: #d5deec; }
            .section-title { margin: 26px 2px 12px; font-size: 18px; }
            #file-list { display: grid; gap: 10px; }
            .file { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 16px; background: white; border: 1px solid #e1e6ee; border-radius: 14px; }
            .file-name { min-width: 0; overflow-wrap: anywhere; font-weight: 600; }
            .file-size { margin-top: 4px; color: #687588; font-size: 13px; }
            a.download { flex: 0 0 auto; padding: 10px 14px; border-radius: 10px; background: #2563eb; color: white; text-decoration: none; font-weight: 600; }
            #empty { padding: 24px; text-align: center; color: #687588; background: white; border: 1px dashed #c8d1df; border-radius: 14px; }
            .upload-panel { padding: 18px; background: white; border: 1px solid #e1e6ee; border-radius: 14px; }
            input[type=file] { width: 100%; margin: 8px 0 12px; }
            button.upload { width: 100%; padding: 12px 16px; border: 0; border-radius: 10px; background: #16804a; color: white; font-weight: 700; font-size: 15px; }
            button.upload:disabled { opacity: .55; }
            #upload-status { margin-top: 10px; color: #526174; font-size: 13px; overflow-wrap: anywhere; }
            .progress { height: 8px; margin-top: 10px; border-radius: 8px; background: #e5eaf1; overflow: hidden; }
            #progress-bar { width: 0; height: 100%; background: #16804a; transition: width .15s; }
            .speed-panel { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-top:14px; }
            .speed { padding:13px; border-radius:12px; background:#fff; border:1px solid #e1e6ee; }
            .speed strong { display:block; font-size:18px; margin-top:4px; }
            .speed small { color:#687588; }
            footer { margin-top: 18px; color: #758195; font-size: 12px; text-align: center; }
          </style>
        </head>
        <body>
          <main>
            <header>
              <h1>Little Bird Sharing</h1>
              <p>Files shared from the host device</p>
            </header>
            <section id="pin-panel" class="upload-panel">
              <h2>Enter host PIN</h2>
              <input id="pin-input" type="password" inputmode="numeric" autocomplete="current-password" placeholder="4–12 digit PIN" maxlength="12">
              <button class="upload" id="pin-button" type="button">Connect securely</button>
              <div id="pin-status" role="status"></div>
            </section>
            <div id="protected-content" hidden>
            <h2 class="section-title">Live transfer speed</h2>
            <section class="speed-panel">
              <div class="speed"><small>Upload from this phone</small><strong id="upload-speed">0 B/s</strong></div>
              <div class="speed"><small>Download to this phone</small><strong id="download-speed">0 B/s</strong></div>
            </section>
            <h2 class="section-title">Upload files to this phone</h2>
            <section class="upload-panel">
              <input id="upload-input" type="file" multiple>
              <button class="upload" id="upload-button" type="button">Upload selected files</button>
              <div class="progress"><div id="progress-bar"></div></div>
              <div id="upload-status" role="status">Choose one or more files to send to the host.</div>
            </section>
            <h2 class="section-title">Available files</h2>
            <section id="file-list"><div id="empty">Loading files…</div></section>
            </div>
            <footer>Enter the PIN set on the host phone to access shared files.</footer>
          </main>
          <script>
            const list = document.getElementById('file-list');
            const pinPanel = document.getElementById('pin-panel');
            const protectedContent = document.getElementById('protected-content');
            const pinInput = document.getElementById('pin-input');
            const pinStatus = document.getElementById('pin-status');
            let authToken = localStorage.getItem('lb-sharing-token') || '';
            async function authenticate() {
              pinStatus.textContent = 'Checking PIN…';
              try {
                const response = await fetch('/auth', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body:new URLSearchParams({pin:pinInput.value})});
                if (!response.ok) throw new Error('Incorrect PIN. Try again.');
                const data = await response.json();
                authToken = data.token;
                localStorage.setItem('lb-sharing-token', authToken);
                pinPanel.hidden = true;
                protectedContent.hidden = false;
                refreshFiles();
              } catch (error) { pinStatus.textContent = error.message; }
            }
            document.getElementById('pin-button').addEventListener('click', authenticate);
            pinInput.addEventListener('keydown', event => { if (event.key === 'Enter') authenticate(); });
            if (authToken) { pinPanel.hidden = true; protectedContent.hidden = false; }
            function authHeaders() { return {'X-Auth-Token':authToken}; }
            const uploadInput = document.getElementById('upload-input');
            const uploadButton = document.getElementById('upload-button');
            const uploadStatus = document.getElementById('upload-status');
            const progressBar = document.getElementById('progress-bar');
            uploadButton.addEventListener('click', async () => {
              const files = Array.from(uploadInput.files || []);
              if (!files.length) { uploadStatus.textContent = 'Select at least one file first.'; return; }
              uploadButton.disabled = true;
              let completed = 0;
              for (const file of files) {
                uploadStatus.textContent = 'Uploading ' + file.name + ' (' + (completed + 1) + '/' + files.length + ')…';
                try {
                  await new Promise((resolve, reject) => {
                    const form = new FormData();
                    form.append('file', file, file.name);
                    const xhr = new XMLHttpRequest();
                    xhr.open('POST', '/upload');
                    xhr.setRequestHeader('X-Auth-Token', authToken);
                    let reportedBytes = 0;
                    xhr.upload.onprogress = event => {
                      if (event.lengthComputable) {
                        const overall = ((completed + event.loaded / event.total) / files.length) * 100;
                        progressBar.style.width = overall + '%';
                      }
                      const delta = event.loaded - reportedBytes;
                      if (delta > 0) {
                        reportedBytes = event.loaded;
                        fetch('/api/upload-progress?delta=' + delta, {
                          method: 'GET',
                          headers: authHeaders(),
                          cache: 'no-store'
                        }).catch(() => {});
                      }
                    };
                    xhr.onload = () => xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error(xhr.responseText || 'Upload failed'));
                    xhr.onerror = () => reject(new Error('Network error during upload'));
                    xhr.send(form);
                  });
                  completed++;
                  progressBar.style.width = (completed / files.length * 100) + '%';
                } catch (error) {
                  uploadStatus.textContent = 'Failed: ' + file.name + '. ' + error.message;
                  uploadButton.disabled = false;
                  return;
                }
              }
              uploadStatus.textContent = completed + ' file(s) uploaded successfully.';
              uploadInput.value = '';
              uploadButton.disabled = false;
            });
            function formatSize(bytes) {
              if (!bytes || bytes < 0) return 'Size unavailable';
              const units = ['B', 'KB', 'MB', 'GB', 'TB'];
              let value = bytes, unit = 0;
              while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
              return value.toFixed(unit === 0 ? 0 : 1) + ' ' + units[unit];
            }
            function formatRate(bytes) {
              if (bytes < 1024) return bytes + ' B/s';
              if (bytes < 1024*1024) return (bytes/1024).toFixed(1) + ' KB/s';
              return (bytes/(1024*1024)).toFixed(2) + ' MB/s';
            }
            async function refreshSpeed() {
              if (!authToken) return;
              try {
                const response = await fetch('/api/status', {cache:'no-store', headers:authHeaders()});
                if (!response.ok) return;
                const data = await response.json();
                document.getElementById('upload-speed').textContent = formatRate(data.uploadBytesPerSecond);
                document.getElementById('download-speed').textContent = formatRate(data.downloadBytesPerSecond);
              } catch (_) {}
            }
            async function refreshFiles() {
              try {
                const response = await fetch('/api/files', {cache:'no-store', headers:authHeaders()});
                if (response.status === 401) { localStorage.removeItem('lb-sharing-token'); authToken=''; pinPanel.hidden=false; protectedContent.hidden=true; throw new Error('Please enter the host PIN again.'); }
                if (!response.ok) throw new Error('Could not load files');
                const data = await response.json();
                list.replaceChildren();
                if (!data.files.length) {
                  const empty = document.createElement('div');
                  empty.id = 'empty';
                  empty.textContent = 'No files have been added by the host yet.';
                  list.appendChild(empty);
                  return;
                }
                data.files.forEach(file => {
                  const card = document.createElement('article');
                  card.className = 'file';
                  const info = document.createElement('div');
                  info.style.minWidth = '0';
                  const name = document.createElement('div');
                  name.className = 'file-name';
                  name.textContent = file.name;
                  const size = document.createElement('div');
                  size.className = 'file-size';
                  size.textContent = formatSize(file.size);
                  info.append(name, size);
                  const link = document.createElement('a');
                  link.className = 'download';
                  link.href = '/download/' + encodeURIComponent(file.id) + '?token=' + encodeURIComponent(authToken);
                  link.textContent = 'Download';
                  card.append(info, link);
                  list.appendChild(card);
                });
              } catch (error) {
                list.replaceChildren();
                const empty = document.createElement('div');
                empty.id = 'empty';
                empty.textContent = 'Unable to load the file list. Refresh this page to retry.';
                list.appendChild(empty);
              }
            }
            if (authToken) { refreshFiles(); refreshSpeed(); }
            setInterval(() => { if (authToken) { refreshFiles(); refreshSpeed(); } }, 1000);
          </script>
        </body>
        </html>
    """.trimIndent()

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MAX_UPLOAD_BYTES = 500L * 1024L * 1024L
    }
}

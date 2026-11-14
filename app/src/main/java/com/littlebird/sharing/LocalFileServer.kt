package com.littlebird.sharing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.util.UUID
import java.net.URLEncoder

data class SharedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
)

data class ReceivedFile(
    val name: String,
    val size: Long,
    val path: String
)

class LocalFileServer(
    private val context: Context,
    private val getSharedFiles: () -> List<SharedFile>,
    private val onFileReceived: (ReceivedFile) -> Unit = {},
    port: Int = DEFAULT_PORT
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/health" ->
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")

                session.uri == "/api/files" -> serveFileList()

                session.uri == "/upload" && session.method == Method.POST -> serveUpload(session)

                session.uri.startsWith("/download/") -> serveDownload(session.uri)

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

            val receivedDirectory = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                "Received"
            )
            if (!receivedDirectory.exists() && !receivedDirectory.mkdirs()) {
                tempFile.delete()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot create received files folder")
            }

            val destination = File(receivedDirectory, "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_$safeName")
            tempFile.copyTo(destination, overwrite = false)
            tempFile.delete()

            val received = ReceivedFile(destination.name, destination.length(), destination.absolutePath)
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

        val input: InputStream = context.contentResolver.openInputStream(file.uri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unable to open file")

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
            #empty { padding: 24px; text-align: center; color: #687588; background: white; border: 1px dashed #c8d1df; border-radius: 14px; }\n            .upload-panel { padding: 18px; background: white; border: 1px solid #e1e6ee; border-radius: 14px; }\n            input[type=file] { width: 100%; margin: 8px 0 12px; }\n            button.upload { width: 100%; padding: 12px 16px; border: 0; border-radius: 10px; background: #16804a; color: white; font-weight: 700; font-size: 15px; }\n            button.upload:disabled { opacity: .55; }\n            #upload-status { margin-top: 10px; color: #526174; font-size: 13px; overflow-wrap: anywhere; }\n            .progress { height: 8px; margin-top: 10px; border-radius: 8px; background: #e5eaf1; overflow: hidden; }\n            #progress-bar { width: 0; height: 100%; background: #16804a; transition: width .15s; }
            footer { margin-top: 18px; color: #758195; font-size: 12px; text-align: center; }
          </style>
        </head>
        <body>
          <main>
            <header>
              <h1>Little Bird Sharing</h1>
              <p>Files shared from the host device</p>
            </header>
            <h2 class="section-title">Upload files to this phone</h2>\n            <section class="upload-panel">\n              <input id="upload-input" type="file" multiple>\n              <button class="upload" id="upload-button" type="button">Upload selected files</button>\n              <div class="progress"><div id="progress-bar"></div></div>\n              <div id="upload-status" role="status">Choose one or more files to send to the host.</div>\n            </section>\n            <h2 class="section-title">Available files</h2>
            <section id="file-list"><div id="empty">Loading files…</div></section>
            <footer>File list refreshes automatically every 5 seconds.</footer>
          </main>
          <script>
            const list = document.getElementById('file-list');\n            const uploadInput = document.getElementById('upload-input');\n            const uploadButton = document.getElementById('upload-button');\n            const uploadStatus = document.getElementById('upload-status');\n            const progressBar = document.getElementById('progress-bar');\n            uploadButton.addEventListener('click', async () => {\n              const files = Array.from(uploadInput.files || []);\n              if (!files.length) { uploadStatus.textContent = 'Select at least one file first.'; return; }\n              uploadButton.disabled = true;\n              let completed = 0;\n              for (const file of files) {\n                uploadStatus.textContent = 'Uploading ' + file.name + ' (' + (completed + 1) + '/' + files.length + ')…';\n                try {\n                  await new Promise((resolve, reject) => {\n                    const form = new FormData();\n                    form.append('file', file, file.name);\n                    const xhr = new XMLHttpRequest();\n                    xhr.open('POST', '/upload');\n                    xhr.upload.onprogress = event => {\n                      if (event.lengthComputable) {\n                        const overall = ((completed + event.loaded / event.total) / files.length) * 100;\n                        progressBar.style.width = overall + '%';\n                      }\n                    };\n                    xhr.onload = () => xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error(xhr.responseText || 'Upload failed'));\n                    xhr.onerror = () => reject(new Error('Network error during upload'));\n                    xhr.send(form);\n                  });\n                  completed++;\n                  progressBar.style.width = (completed / files.length * 100) + '%';\n                } catch (error) {\n                  uploadStatus.textContent = 'Failed: ' + file.name + '. ' + error.message;\n                  uploadButton.disabled = false;\n                  return;\n                }\n              }\n              uploadStatus.textContent = completed + ' file(s) uploaded successfully.';\n              uploadInput.value = '';\n              uploadButton.disabled = false;\n            });
            function formatSize(bytes) {
              if (!bytes || bytes < 0) return 'Size unavailable';
              const units = ['B', 'KB', 'MB', 'GB', 'TB'];
              let value = bytes, unit = 0;
              while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
              return value.toFixed(unit === 0 ? 0 : 1) + ' ' + units[unit];
            }
            async function refreshFiles() {
              try {
                const response = await fetch('/api/files', { cache: 'no-store' });
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
                  link.href = '/download/' + encodeURIComponent(file.id);
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
            refreshFiles();
            setInterval(refreshFiles, 5000);
          </script>
        </body>
        </html>
    """.trimIndent()

    companion object {
        const val DEFAULT_PORT = 8080\n        private const val MAX_UPLOAD_BYTES = 500L * 1024L * 1024L
    }
}

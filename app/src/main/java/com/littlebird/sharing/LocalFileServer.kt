package com.littlebird.sharing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder

data class SharedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
)

class LocalFileServer(
    private val context: Context,
    private val getSharedFiles: () -> List<SharedFile>,
    port: Int = DEFAULT_PORT
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/health" ->
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")

                session.uri == "/api/files" -> serveFileList()

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

    private fun serveDownload(path: String): Response {
        val id = path.removePrefix("/download/").toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file ID")

        val file = getSharedFiles().getOrNull(id)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        val input: InputStream = context.contentResolver.openInputStream(file.uri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unable to open file")

        val response = newFixedLengthResponse(
            Response.Status.OK,
            file.mimeType.ifBlank { "application/octet-stream" },
            input,
            file.size
        )
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
            footer { margin-top: 18px; color: #758195; font-size: 12px; text-align: center; }
          </style>
        </head>
        <body>
          <main>
            <header>
              <h1>Little Bird Sharing</h1>
              <p>Files shared from the host device</p>
            </header>
            <h2 class="section-title">Available files</h2>
            <section id="file-list"><div id="empty">Loading files…</div></section>
            <footer>File list refreshes automatically every 5 seconds.</footer>
          </main>
          <script>
            const list = document.getElementById('file-list');
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
        const val DEFAULT_PORT = 8080
    }
}

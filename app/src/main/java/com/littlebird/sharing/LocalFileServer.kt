package com.littlebird.sharing

import fi.iki.elonen.NanoHTTPD

class LocalFileServer(port: Int = DEFAULT_PORT) : NanoHTTPD("0.0.0.0", port) {
    override fun serve(session: IHTTPSession): Response {
        return if (session.uri == "/health") {
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } else {
            newFixedLengthResponse(
                Response.Status.OK,
                MIME_HTML,
                """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta charset="utf-8">
                  <title>Little Bird Sharing</title>
                </head>
                <body style="font-family:system-ui;padding:2rem;max-width:42rem;margin:auto">
                  <h1>Little Bird Sharing</h1>
                  <p>The host server is running successfully.</p>
                  <p>File sharing will be available in the next development stage.</p>
                </body>
                </html>
                """.trimIndent()
            )
        }
    }

    companion object { const val DEFAULT_PORT = 8080 }
}

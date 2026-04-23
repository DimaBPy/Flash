package com.example.flash.transfer

import android.content.Context
import android.net.Uri
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import java.net.ServerSocket

class FileServer(private val scope: CoroutineScope) {

    private var server: ApplicationEngine? = null

    var port: Int = 0
        private set

    suspend fun start(token: String, fileUri: Uri, context: Context): Int {
        stop()
        port = findFreePort()
        server = embeddedServer(CIO, port = port) {
            routing {
                get("/transfer/{token}") {
                    val requestToken = call.parameters["token"]
                    if (requestToken != token) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val cr = context.contentResolver
                    val mimeType = cr.getType(fileUri) ?: "application/octet-stream"
                    val fileName = fileUri.lastPathSegment ?: "file"
                    val fileSize = cr.openFileDescriptor(fileUri, "r")?.use { it.statSize } ?: 0L

                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename=\"$fileName\""
                    )
                    call.response.header(HttpHeaders.ContentLength, fileSize.toString())

                    call.respondOutputStream(contentType = ContentType.parse(mimeType)) {
                        cr.openInputStream(fileUri)?.use { input ->
                            input.copyTo(this)
                        }
                    }
                }
            }
        }.start(wait = false)

        return port
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

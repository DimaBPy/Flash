package com.example.flash.transfer

import android.content.Context
import android.net.Uri
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class FileServer(private val scope: CoroutineScope) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val entries = CopyOnWriteArrayList<Pair<String, Uri>>()
    private var appContext: Context? = null

    var port: Int = 0
        private set

    val fileCount: Int get() = entries.size

    suspend fun start(token: String, uris: List<Uri>, context: Context): Int {
        stop()
        entries.clear()
        appContext = context.applicationContext
        uris.forEach { entries.add(token to it) }
        port = findFreePort()
        server = embeddedServer(CIO, port = port) {
            routing {
                get("/transfer/{token}/{index}") {
                    val requestToken = call.parameters["token"] ?: ""
                    val index = call.parameters["index"]?.toIntOrNull() ?: 0
                    val entry = entries.getOrNull(index)
                    if (entry == null || entry.first != requestToken) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val fileUri = entry.second
                    val cr = appContext!!.contentResolver
                    val mimeType = cr.getType(fileUri) ?: "application/octet-stream"
                    val fileName = fileUri.lastPathSegment ?: "file"
                    val fileSize = cr.openFileDescriptor(fileUri, "r")?.use { it.statSize } ?: 0L

                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
                    call.response.header(HttpHeaders.ContentLength, fileSize.toString())
                    call.respondOutputStream(contentType = ContentType.parse(mimeType)) {
                        cr.openInputStream(fileUri)?.use { input -> input.copyTo(this) }
                    }
                }
            }
        }.start(wait = false)
        return port
    }

    fun addUri(token: String, uri: Uri) {
        entries.add(token to uri)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
        port = 0
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

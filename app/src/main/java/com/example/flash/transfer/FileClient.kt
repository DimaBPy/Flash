package com.example.flash.transfer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.File

class FileClient {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun download(
        ip: String,
        port: Int,
        token: String,
        destDir: File,
        onProgress: (Float) -> Unit
    ): File {
        destDir.mkdirs()

        val response: HttpResponse = client.get("http://$ip:$port/transfer/$token")
        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 1L
        val rawDisposition = response.headers[HttpHeaders.ContentDisposition] ?: ""
        val fileName = extractFileName(rawDisposition)
            ?: "received_${System.currentTimeMillis()}"

        val dest = File(destDir, fileName)
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8192)
        var bytesRead = 0L

        dest.outputStream().use { out ->
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                    bytesRead += count
                    onProgress((bytesRead.toFloat() / contentLength).coerceIn(0f, 1f))
                }
            }
        }

        onProgress(1f)
        return dest
    }

    private fun extractFileName(disposition: String): String? {
        return disposition
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("filename=") }
            ?.removePrefix("filename=")
            ?.trim('"')
    }

    fun close() = client.close()
}

package com.example.flash.transfer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.File
import java.io.IOException

class FileClient {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun downloadAll(
        ip: String,
        port: Int,
        token: String,
        fileCount: Int,
        destDir: File,
        onProgress: (Float) -> Unit
    ): List<File> {
        val progresses = FloatArray(fileCount)
        val files = arrayOfNulls<File>(fileCount)
        val lock = Any()

        supervisorScope {
            (0 until fileCount).forEach { index ->
                launch {
                    runCatching {
                        download(ip, port, token, index, destDir) { progress ->
                            synchronized(lock) {
                                progresses[index] = progress
                                onProgress(progresses.average().toFloat())
                            }
                        }
                    }.onSuccess { files[index] = it }
                }
            }
        }

        onProgress(1f)
        return files.filterNotNull()
    }

    suspend fun download(
        ip: String,
        port: Int,
        token: String,
        index: Int,
        destDir: File,
        onProgress: (Float) -> Unit
    ): File {
        destDir.mkdirs()

        val response: HttpResponse = client.get("http://$ip:$port/transfer/$token/$index")

        if (!response.status.isSuccess()) {
            throw IOException("Transfer failed: HTTP ${response.status.value}")
        }

        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val rawDisposition = response.headers[HttpHeaders.ContentDisposition] ?: ""
        val baseName = sanitizeFileName(extractFileName(rawDisposition))
            ?: "received_${System.currentTimeMillis()}"
        val fileName = "${index}_$baseName"

        val dest = File(destDir, fileName)
        require(dest.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
            "Resolved destination escapes destDir"
        }

        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8192)
        var bytesRead = 0L

        dest.outputStream().use { out ->
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                    bytesRead += count
                    if (contentLength != null && contentLength > 0) {
                        onProgress((bytesRead.toFloat() / contentLength).coerceIn(0f, 1f))
                    }
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

    private fun sanitizeFileName(raw: String?): String? {
        if (raw == null) return null
        val base = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        return if (base.isBlank() || base == "." || base == "..") null else base
    }

    fun close() = client.close()
}

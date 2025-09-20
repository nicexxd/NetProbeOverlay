package com.example.netprobeoverlay.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

object NetProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun measureLatency(url: String = "https://www.gstatic.com/generate_204"): Long = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val end = System.nanoTime()
                    return@withContext TimeUnit.NANOSECONDS.toMillis(end - start)
                }
            }
        } catch (_: Exception) { }
        return@withContext -1L
    }

    suspend fun measureBandwidth(
        url: String = "https://speed.cloudflare.com/__down?bytes=5000000",
        durationMillis: Long = 3000
    ): Double = withContext(Dispatchers.IO) {
        // 限时下载测吞吐，返回 Mbps
        val req = Request.Builder().url(url).get().build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext -1.0
                val body = resp.body ?: return@withContext -1.0
                val buf = ByteArray(16 * 1024)
                var total = 0L
                val start = System.nanoTime()
                val stream = body.byteStream()
                while (true) {
                    val now = System.nanoTime()
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(now - start)
                    if (elapsed >= durationMillis) break
                    val read = stream.read(buf)
                    if (read <= 0) break
                    total += read
                }
                val end = System.nanoTime()
                val seconds = (end - start) / 1_000_000_000.0
                val mbps = if (seconds > 0) (total * 8 / 1_000_000.0) / seconds else -1.0
                return@withContext String.format("%.2f", mbps).toDouble()
            }
        } catch (_: Exception) { }
        return@withContext -1.0
    }

    suspend fun measureUploadBandwidth(
        url: String = "https://speed.cloudflare.com/__up",
        durationMillis: Long = 3000
    ): Double = withContext(Dispatchers.IO) {
        // 限时上行测吞吐，返回 Mbps（基于分块POST，服务器接收即计入）
        val contentType = "application/octet-stream".toMediaType()
        val chunk = ByteArray(16 * 1024) // 16KB
        val start = System.nanoTime()
        var total = 0L
        val body = object : RequestBody() {
            override fun contentType() = contentType
            override fun writeTo(sink: BufferedSink) {
                while (true) {
                    val now = System.nanoTime()
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(now - start)
                    if (elapsed >= durationMillis) break
                    sink.write(chunk)
                    total += chunk.size
                }
                sink.flush()
            }
            // 返回 -1 以启用 chunked 传输
            override fun contentLength(): Long = -1
        }
        val req = Request.Builder().url(url).post(body).build()
        try {
            client.newCall(req).execute().use { resp ->
                val end = System.nanoTime()
                val seconds = (end - start) / 1_000_000_000.0
                val mbps = if (seconds > 0) (total * 8 / 1_000_000.0) / seconds else -1.0
                return@withContext String.format("%.2f", mbps).toDouble()
            }
        } catch (_: Exception) { }
        return@withContext -1.0
    }
}
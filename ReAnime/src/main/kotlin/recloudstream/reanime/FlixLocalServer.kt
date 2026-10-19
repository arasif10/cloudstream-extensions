package recloudstream.reanime

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * FlixCloud serves an encrypted HLS playlist: the manifest only decrypts through
 * enc-dec.app, the segments wear a fake WebP/PNG header and are XOR-masked.
 *
 * The player cannot do any of that, so the plugin answers it from 127.0.0.1: the
 * playlist is fetched, decrypted and rewritten so every child URI points back at
 * us, and every segment is stripped of its fake header and unmasked on the way
 * through. Same approach as the Aniyomi extension's FlixProxyServer, without a
 * third-party HTTP server dependency.
 */
internal class FlixLocalServer(
    private val upstreamHeaders: Map<String, String>,
) {
    companion object {
        const val FLIX_CLOUD = "https://flixcloud.cc"
        const val ENC_DEC = "https://enc-dec.app"

        /** Mask used when neither the shipped script nor a previous run provided one. */
        private val FALLBACK_MASK = byteArrayOf(
            157.toByte(), 42, 241.toByte(), 71, 179.toByte(), 142.toByte(), 92, 112,
            166.toByte(), 25, 228.toByte(), 59, 216.toByte(), 98, 15, 197.toByte(),
        )

        private val URI_REGEX = Regex("URI=\"(.*?)\"")
        private val BANDWIDTH_REGEX = Regex("BANDWIDTH=(\\d+)")
        private val AVERAGE_BANDWIDTH_REGEX = Regex("AVERAGE-BANDWIDTH=(\\d+)")

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        /** Detect the fake image header: 12 for WebP, 8 for PNG, 0 when raw. */
        private fun headerSize(data: ByteArray): Int = when {
            data.size >= 12 &&
                data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
                data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
                data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
                data[10] == 0x42.toByte() && data[11] == 0x50.toByte() -> 12

            data.size >= 4 &&
                data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> 8

            else -> 0
        }
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newFixedThreadPool(8)

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Updated whenever the site ships a new mask; every segment request reads it. */
    @Volatile
    var segmentMask: ByteArray = FALLBACK_MASK

    val port: Int get() = server.localPort

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                pool.execute { serve(socket) }
            }
        }, "reanime-flix-proxy").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    /** The playlist the player is handed: everything under it is resolved locally. */
    fun playlistUrl(url: String, wPayload: String): String =
        "http://127.0.0.1:$port/p?u=${encode(url)}&w=${encode(wPayload)}"

    fun subtitleUrl(url: String): String = "http://127.0.0.1:$port/s?u=${encode(url)}"

    private fun serve(socket: Socket) {
        socket.use { open ->
            try {
                val reader = open.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1) ?: return
                val (contentType, body, status) = route(path)
                val head = "HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                val out = open.getOutputStream()
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            } catch (_: Exception) {
                // A player dropping a connection is normal; the next request retries.
            }
        }
    }

    private fun route(path: String): Triple<String, ByteArray, String> {
        val route = path.substringBefore('?')
        val params = runCatching {
            path.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        }.getOrDefault(emptyMap())

        val target = params["u"] ?: return Triple("text/plain", "missing url".toByteArray(), "400 Bad Request")
        val wPayload = params["w"].orEmpty()

        return runCatching {
            when {
                route.startsWith("/s") -> subtitle(target)
                target.contains(".m3u8") -> playlist(target, wPayload)
                else -> segment(target)
            }
        }.getOrElse {
            Triple("text/plain", it.toString().toByteArray(), "502 Bad Gateway")
        }
    }

    private fun fetch(url: String, refererIsEncDec: Boolean = url.contains(ENC_DEC)): ByteArray {
        val headers = upstreamHeaders.toMutableMap().apply {
            put("Accept", "*/*")
            remove("Origin")
            remove("Referer")
            remove("Sec-Fetch-Dest")
            remove("Sec-Fetch-Mode")
            remove("Sec-Fetch-Site")
            remove("Accept-Encoding")
            if (refererIsEncDec) {
                put("Origin", ENC_DEC)
                put("Referer", "$ENC_DEC/")
            } else {
                put("Origin", FLIX_CLOUD)
                put("Referer", "$FLIX_CLOUD/")
            }
        }
        val headerBuilder = okhttp3.Headers.Builder()
        headers.forEach { (key, value) -> headerBuilder.add(key, value) }
        val request = Request.Builder().url(url).headers(headerBuilder.build()).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("HTTP ${response.code} from ${url.substringBefore('?')}")
        }
        return response.use { it.body?.bytes() ?: ByteArray(0) }
    }

    /** The playlist decrypted by enc-dec, with every child URI pointing back here. */
    private fun playlist(url: String, wPayload: String): Triple<String, ByteArray, String> {
        val decryptedUrl = if (url.contains(ENC_DEC)) {
            url
        } else {
            "$ENC_DEC/api/parse-flixcloud?url=${encode(url).replace("+", "%20")}" +
                "&w_payload=${encode(wPayload).replace("+", "%20")}"
        }
        val text = String(fetch(decryptedUrl, url.contains(ENC_DEC)), Charsets.UTF_8)
        val parent = runCatching {
            if (url.contains(ENC_DEC)) url.toHttpUrl().queryParameter("url")?.toHttpUrl() else null
        }.getOrNull() ?: url.toHttpUrl()

        val rewritten = text.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> ""
                trimmed.startsWith("#") -> {
                    val fixed = fixBandwidth(trimmed)
                    if (!fixed.contains("URI=\"")) {
                        fixed
                    } else {
                        val uri = URI_REGEX.find(fixed)?.groupValues?.get(1).orEmpty()
                        if (uri.isEmpty()) {
                            fixed
                        } else {
                            val resolved = parent.resolve(uri)?.toString() ?: uri
                            fixed.replace(
                                URI_REGEX,
                                "URI=\"${playlistUrl(withToken(resolved, url), wPayload)}\"",
                            )
                        }
                    }
                }
                else -> {
                    val resolved = parent.resolve(trimmed)?.toString() ?: trimmed
                    playlistUrl(withToken(resolved, url), wPayload)
                }
            }
        }
        return Triple("application/vnd.apple.mpegurl", rewritten.toByteArray(), "200 OK")
    }

    /** A segment, minus the fake image header and unmasked. */
    private fun segment(url: String): Triple<String, ByteArray, String> {
        val bytes = fetch(url)
        val skip = headerSize(bytes)
        if (skip == 0) return Triple("video/mp2t", bytes, "200 OK")

        val body = bytes.copyOfRange(skip, bytes.size)
        val mask = segmentMask
        if (mask.size == 16) {
            for (index in body.indices) {
                body[index] = (body[index].toInt() xor mask[index and 15].toInt()).toByte()
            }
        }
        return Triple("video/mp2t", body, "200 OK")
    }

    private fun subtitle(url: String): Triple<String, ByteArray, String> {
        val body = fetch(url, refererIsEncDec = false)
        val type = when {
            url.contains(".ass", ignoreCase = true) -> "text/x-ssa"
            url.contains(".vtt", ignoreCase = true) -> "text/vtt"
            url.contains(".srt", ignoreCase = true) -> "application/x-subrip"
            else -> "text/plain"
        }
        return Triple(type, body, "200 OK")
    }

    private fun fixBandwidth(line: String): String {
        if (!line.startsWith("#EXT-X-STREAM-INF")) return line
        val peak = BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return line
        if (peak >= 100_000L) return line
        val average = AVERAGE_BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()
        val repaired = if (average != null && average > 100_000L) average else peak * 1000L
        return line.replace(BANDWIDTH_REGEX, "BANDWIDTH=$repaired")
    }

    /**
     * Segment and playlist URLs need the token of the request that produced them;
     * enc-dec wrappers carry it in their own query string.
     */
    private fun withToken(segmentUrl: String, parentUrl: String): String = runCatching {
        val parsed = segmentUrl.toHttpUrl()
        var token = parsed.queryParameter("token")
        if (token == null) {
            var current = parentUrl
            repeat(3) {
                val http = current.toHttpUrl()
                if (token == null) token = http.queryParameter("token")
                if (token == null) http.queryParameter("url")?.let { current = it }
            }
        }
        if (token == null || parsed.queryParameter("token") != null) {
            segmentUrl
        } else {
            parsed.newBuilder().addQueryParameter("token", token).build().toString()
        }
    }.getOrDefault(segmentUrl)
}

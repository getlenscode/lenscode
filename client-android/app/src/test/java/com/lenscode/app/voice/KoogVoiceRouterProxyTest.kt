package com.lenscode.app.voice

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Pins how the router addresses lenscode-bridge's /gemini proxy: Koog must append
 * `v1beta/models/<id>:generateContent` under the proxy base path, and the device
 * credentials must ride along on every request. The stub answers 500 on purpose —
 * only the request shape is under test. Koog surfaces an HTTP error as an exception
 * (WorkspaceRoot.submitVoiceCommand catches it and falls back to the local matcher).
 */
class KoogVoiceRouterProxyTest {

    private class Seen(val path: String, val query: String?, val headers: Map<String, String>)

    // Plain sockets rather than com.sun.net.httpserver: the Android unit-test
    // classpath doesn't expose it, and a stub this small needs no dependency.
    private lateinit var server: ServerSocket
    private val seen = CopyOnWriteArrayList<Seen>()

    @Before
    fun startStub() {
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (ignored: java.io.IOException) {
                    return@thread
                }
                socket.use {
                    val input = it.getInputStream()
                    val target = readLine(input).split(" ")[1]
                    val headers = generateSequence { readLine(input) }
                        .takeWhile { line -> line.isNotEmpty() }
                        .associate { line -> line.substringBefore(":").lowercase() to line.substringAfter(":").trim() }
                    val uri = URI(target)
                    seen += Seen(uri.path, uri.rawQuery, headers)
                    // Content-Length counts bytes and the prompt has multibyte characters,
                    // so drain raw bytes — a char-based reader would wait for bytes that never come.
                    input.readNBytes(headers["content-length"]?.toInt() ?: 0)
                    val body = """{"error":{"code":500,"message":"stub"}}"""
                    it.getOutputStream().write(
                        ("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                    )
                }
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        return line.toString()
    }

    @After
    fun stopStub() = server.close()

    @Test
    fun `requests go to the proxy path with device credentials and no real key`() = runBlocking {
        var token = "tok-1"
        val router = KoogVoiceRouter(
            apiKey = "via-lenscode-bridge",
            modelId = "gemini-test",
            baseUrl = com.lenscode.app.net.GeminiBridgeUrls.proxyBase("ws://127.0.0.1:${server.localPort}"),
            extraHeaders = { mapOf("X-LensCode-Device" to "xr-1", "X-LensCode-Token" to token) },
        )
        val context = VoiceRouterContext("home", null, false, null)

        val first = runCatching { router.route("open the diff", context) }
        assertTrue("the stub's 500 must not look like a success", first.isFailure || first.getOrThrow().isFailure)
        token = "tok-2"
        runCatching { router.route("open the diff", context) }

        val req = seen.first()
        assertEquals("/gemini/v1beta/models/gemini-test:generateContent", req.path)
        assertEquals("key=via-lenscode-bridge", req.query)
        assertEquals("xr-1", req.headers["x-lenscode-device"])
        assertEquals("tok-1", req.headers["x-lenscode-token"])
        assertEquals("headers are re-read per request", "tok-2", seen.last().headers["x-lenscode-token"])
    }
}

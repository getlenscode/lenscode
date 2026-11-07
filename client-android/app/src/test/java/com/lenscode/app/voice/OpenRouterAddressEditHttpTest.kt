package com.lenscode.app.voice

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The real request `editAddress` sends, against a stub that answers like OpenRouter's chat completions:
 * the prompt carries the current address and the transcript, and the reply is parsed and checked.
 */
class OpenRouterAddressEditHttpTest {
    private lateinit var server: ServerSocket
    private val bodies = CopyOnWriteArrayList<String>()
    private val paths = CopyOnWriteArrayList<String>()

    @Volatile private var status = 200
    @Volatile private var content = """{"address":"http://100.96.103.61:8000/"}"""

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
                    paths += readLine(input).split(" ")[1]
                    val headers = generateSequence { readLine(input) }
                        .takeWhile { line -> line.isNotEmpty() }
                        .associate { line -> line.substringBefore(":").lowercase() to line.substringAfter(":").trim() }
                    bodies += String(input.readNBytes(headers["content-length"]?.toInt() ?: 0), Charsets.UTF_8)
                    val reply = if (status == 200) {
                        JSONObject().put(
                            "choices",
                            org.json.JSONArray().put(
                                JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content)),
                            ),
                        ).toString()
                    } else {
                        """{"error":{"code":$status,"message":"stub failure"}}"""
                    }
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    it.getOutputStream().write(
                        ("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray() + bytes,
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

    private fun router() = OpenRouterVoiceRouter(
        modelId = "vendor/test-model",
        baseUrl = "http://127.0.0.1:${server.localPort}/openrouter/",
    )

    @Test
    fun `the request carries the address, the words, the rules and the right model`() = runBlocking {
        router().editAddress("http://100.96.103.69:8000/", "it should be 103.61 not 103.69")

        assertEquals("/openrouter/chat/completions", paths.single())
        val body = JSONObject(bodies.single())
        assertEquals("vendor/test-model", body.getString("model"))
        val messages = body.getJSONArray("messages")
        val system = messages.getJSONObject(0).getString("content")
        val user = messages.getJSONObject(1).getString("content")
        assertTrue(system.contains("it should be 103.61 not 103.69")) // the worked example
        assertTrue(user.contains("Current address: http://100.96.103.69:8000/"))
        assertTrue(user.contains("User said: \"it should be 103.61 not 103.69\""))
    }

    @Test
    fun `a good reply becomes the new address`() = runBlocking {
        assertEquals("http://100.96.103.61:8000/", router().editAddress("http://100.96.103.69:8000/", "x").getOrThrow())
    }

    @Test
    fun `a reply wrapped in fences still works`() = runBlocking {
        content = "```json\n{\"address\":\"http://h:1/docs\"}\n```"
        assertEquals("http://h:1/docs", router().editAddress("http://h:1", "x").getOrThrow())
    }

    @Test
    fun `an unusable reply is a failure so the field is never overwritten`() = runBlocking {
        content = """{"address":"not an address at all"}"""
        assertTrue(router().editAddress("http://h:1", "x").isFailure)
        content = "I'm not sure what you meant."
        assertTrue(router().editAddress("http://h:1", "x").isFailure)
    }

    @Test
    fun `an HTTP error is a failure that carries the service's message`() = runBlocking {
        status = 400
        val e = router().editAddress("http://h:1", "x").exceptionOrNull()!!
        assertTrue(e.message!!.contains("HTTP 400"))
        assertTrue(e.message!!.contains("stub failure"))
    }
}

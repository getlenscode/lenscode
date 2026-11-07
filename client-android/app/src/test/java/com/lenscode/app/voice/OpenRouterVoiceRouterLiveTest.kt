package com.lenscode.app.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Not a real automated test (talks to the live OpenRouter API, costs real credit) — a manual
 * verification harness for iterating on the routing grammar/prompt without an APK round-trip.
 * Self-skips unless OPENROUTER_API_KEY is set, so a normal `./gradlew test` never fails on it:
 *   OPENROUTER_API_KEY=sk-or-... ./gradlew :app:testDebugUnitTest --tests "*OpenRouterVoiceRouterLiveTest*"
 * Set OPENROUTER_MODEL to try another model (default z-ai/glm-5.3-flash). Or set OPENROUTER_BASE_URL to a
 * running bridge's proxy (http://127.0.0.1:8137/openrouter/) and no key is needed here.
 */
class OpenRouterVoiceRouterLiveTest {
    @Test
    fun liveProbe() = runBlocking {
        val apiKey = System.getenv("OPENROUTER_API_KEY")
        val baseUrl = System.getenv("OPENROUTER_BASE_URL")
        assumeTrue("skipping: set OPENROUTER_API_KEY, or OPENROUTER_BASE_URL for a bridge proxy", apiKey != null || baseUrl != null)
        val router = OpenRouterVoiceRouter(
            modelId = System.getenv("OPENROUTER_MODEL") ?: com.lenscode.app.net.DEFAULT_OPENROUTER_MODEL,
            baseUrl = baseUrl ?: "https://openrouter.ai/api/v1/",
            apiKey = apiKey,
        )
        val context = VoiceRouterContext(
            activeView = "home",
            diffFileOpen = "package.json",
            terminalSelected = true,
            agentCardOpen = null,
        )
        val phrases = listOf(
            "navigate to code and open line 20 in package.json",
            "accept changes",
            "kill terminal",
            "find the authentication middleware",
            "discard changes",
            "make me a new terminal",
            "what's the weather like",
            "open the diff view and then discard everything",
            "open workspace ui dot kt",
            "add a task to fix the login bug",
            "i want to build a web server in flask can you create a step-by-step plan on what dockerfile and what api endpoints it will have to do admin management",
            "in plan mode add retry logic to the bridge",
            "use dev a",
        )
        for (phrase in phrases) {
            val result = router.route(phrase, context)
            println("\"$phrase\" -> ${result.getOrNull()?.toActions() ?: result}")
        }
    }

    @Test
    fun liveAddressEdits() = runBlocking {
        val apiKey = System.getenv("OPENROUTER_API_KEY")
        val baseUrl = System.getenv("OPENROUTER_BASE_URL")
        assumeTrue("skipping: set OPENROUTER_API_KEY, or OPENROUTER_BASE_URL for a bridge proxy", apiKey != null || baseUrl != null)
        val router = OpenRouterVoiceRouter(
            modelId = System.getenv("OPENROUTER_MODEL") ?: com.lenscode.app.net.DEFAULT_OPENROUTER_MODEL,
            baseUrl = baseUrl ?: "https://openrouter.ai/api/v1/",
            apiKey = apiKey,
        )
        val current = "http://100.96.103.69:8000/"
        val sayings = listOf(
            "it should be 103.61 not 103.69",
            "it should be one oh three dot sixty one not one oh three dot sixty nine",
            "make the last digit one",
            "change the port to 8001",
            "add slash docs",
            "make it https",
            "remove the port",
            "actually it is one ninety two dot one sixty eight dot one dot one fifty seven",
        )
        for (said in sayings) println("ADDR \"$said\" -> ${router.editAddress(current, said)}")
    }
}

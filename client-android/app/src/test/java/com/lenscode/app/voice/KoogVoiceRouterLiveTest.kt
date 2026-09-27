package com.lenscode.app.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Not a real automated test (talks to the live Gemini API, costs real quota)
 * — a manual verification harness for iterating on the routing grammar/prompt
 * without a full APK rebuild+install round-trip each time. Self-skips (via
 * assumeTrue) unless GEMINI_API_KEY is set in the environment, so a normal
 * `./gradlew test` run elsewhere doesn't fail on a missing key:
 *   GEMINI_API_KEY=AIza... ./gradlew :app:testDebugUnitTest --tests "*KoogVoiceRouterLiveTest*"
 */
class KoogVoiceRouterLiveTest {
    @Test
    fun liveProbe() = runBlocking {
        val apiKey = System.getenv("GEMINI_API_KEY")
        assumeTrue("skipping: GEMINI_API_KEY not set", apiKey != null)
        requireNotNull(apiKey)
        val router = KoogVoiceRouter(apiKey, "gemini-3.8-flash")
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
        )
        for (phrase in phrases) {
            val result = router.route(phrase, context)
            val actions = result.getOrNull()?.toActions()
            println("\"$phrase\" -> $result\n    actions: $actions")
        }
    }
}

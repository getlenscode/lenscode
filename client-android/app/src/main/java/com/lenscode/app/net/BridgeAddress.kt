package com.lenscode.app.net

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

const val DEFAULT_BRIDGE_PORT = 8137

sealed class BridgeAddressResult {
    data class Ok(val url: String) : BridgeAddressResult()
    data class Invalid(val message: String) : BridgeAddressResult()
}

private val ADDRESS = Regex(
    """^(?:([a-z]+)://)?([^/:\s]+)(?::(\d{1,5}))?/?$""",
    RegexOption.IGNORE_CASE,
)
private val HOST_CHARS = Regex("""^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$""", RegexOption.IGNORE_CASE)
private const val MAX_PORT = 65535

/**
 * Turns what the user typed or dictated into the WebSocket URL the app dials:
 * "192.168.1.157", "192.168.1.157:9000", "ws://host:8137", "https://host" all work.
 * The port defaults to [DEFAULT_BRIDGE_PORT]. With no scheme given it picks wss when
 * [secureOnly] (release builds, which the platform forbids from using cleartext) and
 * ws otherwise; an explicit ws:// on a secure-only build is refused up front with an
 * explanation rather than failing later as an opaque connection error.
 */
fun normalizeBridgeAddress(input: String, secureOnly: Boolean): BridgeAddressResult {
    val text = input.trim()
    val match = ADDRESS.matchEntire(text)
    return when {
        text.isEmpty() -> BridgeAddressResult.Invalid("Enter the bridge's address, for example 192.168.1.157")
        match == null -> BridgeAddressResult.Invalid(
            "That doesn't look like an address. Try 192.168.1.157 or wss://my-host:8137",
        )
        else -> validateAddress(match, secureOnly)
    }
}

private fun validateAddress(match: MatchResult, secureOnly: Boolean): BridgeAddressResult {
    val (rawScheme, host, rawPort) = match.destructured
    // null = a scheme we don't speak
    val secure = when (rawScheme.lowercase()) {
        "" -> secureOnly
        "wss", "https" -> true
        "ws", "http" -> false
        else -> null
    }
    val port = rawPort.ifEmpty { DEFAULT_BRIDGE_PORT.toString() }.toInt()
    return when {
        secure == null -> BridgeAddressResult.Invalid("Use ws:// or wss:// (or just the host name).")
        !HOST_CHARS.matches(host) ->
            BridgeAddressResult.Invalid("That host name has characters an address can't have.")
        port !in 1..MAX_PORT -> BridgeAddressResult.Invalid("The port must be between 1 and $MAX_PORT.")
        !secure && secureOnly -> BridgeAddressResult.Invalid(
            "This build only connects over a secure address. Run the bridge with TLS and enter wss://$host:$port",
        )
        else -> BridgeAddressResult.Ok("${if (secure) "wss" else "ws"}://$host:$port")
    }
}

/** A sentence a person can act on, for whatever stopped the WebSocket connecting. */
fun describeConnectError(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it.message?.contains("CLEARTEXT", ignoreCase = true) == true } ->
            "This build only allows secure (wss://) addresses."
        chain.any { it is UnknownHostException } -> "Can't find that host. Check the address."
        chain.any { it is SSLException } -> "Secure connection failed — the bridge's certificate isn't trusted."
        chain.any { it is ConnectException || it is SocketTimeoutException } ->
            "Couldn't reach it. Is the bridge running, and on the same network?"
        else -> "Couldn't connect to the bridge."
    }
}

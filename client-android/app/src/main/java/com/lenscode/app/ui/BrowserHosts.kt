package com.lenscode.app.ui

import com.lenscode.app.net.MachineInfo

/**
 * A machine the Browser button can open a dev server on. [host] is what goes in the URL. [certain] is
 * false when it's only a machine's ssh name, which the headset may not be able to resolve.
 */
data class BrowserHost(val id: String, val label: String, val host: String, val certain: Boolean)

private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

/** "wss://100.96.103.69:8443" -> "100.96.103.69". */
fun hostOfBridgeUrl(bridgeUrl: String): String =
    bridgeUrl.substringAfter("://").substringBefore('/').substringBefore(':')

/**
 * The machines a preview can come from: the computer running the bridge and Paseo first (its address is the
 * one the app already uses to reach it, so it is known to work), then each connected remote machine.
 * The bridge never sends a remote's real IP to the headset, so a remote's host is its ssh name, which is
 * reliable only when that is an IP or a full domain name.
 */
fun browserHosts(bridgeUrl: String, machines: List<MachineInfo>): List<BrowserHost> =
    listOf(BrowserHost("local", "This computer", hostOfBridgeUrl(bridgeUrl), certain = true)) +
        machines.filter { it.connected }.map {
            BrowserHost(it.id, it.label.ifBlank { it.id }, it.id, certain = IPV4.matches(it.id) || it.id.contains('.'))
        }

/**
 * Turns what was typed into a URL on [host]: a bare port ("3000") becomes http://host:3000, a
 * localhost/127.0.0.1 address is pointed at [host], and anything else (another host, a full URL) is kept.
 */
fun applyBrowserHost(input: String, host: String): String {
    val text = input.trim()
    if (text.isEmpty()) return text
    if (text.all { it.isDigit() }) return "http://$host:$text"
    val scheme = text.substringBefore("://", "")
    val rest = if (scheme.isEmpty()) text else text.substringAfter("://")
    val authority = rest.substringBefore('/').substringBefore('?')
    val name = authority.substringBefore(':')
    if (name != "localhost" && name != "127.0.0.1") return text
    val pointed = host + rest.removePrefix(name)
    return if (scheme.isEmpty()) "http://$pointed" else "$scheme://$pointed"
}

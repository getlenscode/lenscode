package com.lenscode.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A lenscode-bridge seen on the local network (mDNS `_lenscode._tcp`). */
data class FoundBridge(
    val name: String,
    val host: String,
    val port: Int,
    val secure: Boolean,
    /** Short certificate fingerprint from the advert, if the bridge made its own certificate. */
    val fingerprint: String?,
) {
    /** What the app dials; handed to the same address check as a typed one. */
    val url: String get() = "${if (secure) "wss" else "ws"}://$host:$port"
}

/**
 * Finds bridges on the LAN so nobody has to dictate an IP. The advert is unauthenticated: picking
 * one only fills in the address; the pairing code and the certificate prompt still decide trust.
 */
@Suppress("DEPRECATION")
class BridgeDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _found = MutableStateFlow<List<FoundBridge>>(emptyList())
    val found: StateFlow<List<FoundBridge>> = _found

    private var listener: NsdManager.DiscoveryListener? = null
    // resolveService fails with FAILURE_ALREADY_ACTIVE if two overlap, so resolve one at a time.
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(queue) { queue.addLast(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.value = _found.value.filterNot { it.name == info.serviceName }
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        runCatching { nsd.discoverServices("_lenscode._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        synchronized(queue) { queue.clear() }
        _found.value = emptyList()
    }

    private fun resolveNext() {
        val next = synchronized(queue) {
            if (resolving || queue.isEmpty()) return
            resolving = true
            queue.removeFirst()
        }
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                toFoundBridge(info)?.let { b ->
                    _found.value = _found.value.filterNot { it.name == b.name } + b
                }
                done()
            }
            private fun done() {
                synchronized(queue) { resolving = false }
                resolveNext()
            }
        })
    }

    private fun toFoundBridge(info: NsdServiceInfo): FoundBridge? {
        val host = info.host?.hostAddress ?: return null
        // IPv6 literals would need brackets in a URL; the bridge also advertises IPv4, which resolves separately.
        if (host.contains(':')) return null
        fun txt(key: String) = info.attributes[key]?.toString(Charsets.UTF_8)
        return FoundBridge(
            name = info.serviceName,
            host = host,
            port = info.port,
            secure = txt("secure") == "1",
            fingerprint = txt("fp"),
        )
    }
}

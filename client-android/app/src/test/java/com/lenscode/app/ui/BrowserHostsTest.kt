package com.lenscode.app.ui

import com.lenscode.app.net.MachineInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHostsTest {
    @Test
    fun `the bridge computer is first and uses the address the app already reaches it on`() {
        val hosts = browserHosts("wss://100.96.103.69:8443", emptyList())
        assertEquals(listOf(BrowserHost("local", "This computer", "100.96.103.69", true)), hosts)
    }

    @Test
    fun `only connected remotes are offered and an ssh alias is marked uncertain`() {
        val hosts = browserHosts(
            "ws://192.168.1.157:8137",
            listOf(
                MachineInfo("maclaptop", "maclaptop", true),
                MachineInfo("devbox.tail1.ts.net", "devbox", true),
                MachineInfo("10.0.0.5", "lab", true),
                MachineInfo("offline", "offline", false),
            ),
        )
        assertEquals(listOf("local", "maclaptop", "devbox.tail1.ts.net", "10.0.0.5"), hosts.map { it.id })
        assertFalse(hosts[1].certain)
        assertTrue(hosts[2].certain)
        assertTrue(hosts[3].certain)
    }

    @Test
    fun `a bare port becomes a url on the chosen host`() {
        assertEquals("http://100.96.103.69:3000", applyBrowserHost("3000", "100.96.103.69"))
    }

    @Test
    fun `localhost is pointed at the chosen host and keeps its port and path`() {
        assertEquals("http://10.0.0.5:3000/app?x=1", applyBrowserHost("localhost:3000/app?x=1", "10.0.0.5"))
        assertEquals("https://10.0.0.5:8443", applyBrowserHost("https://127.0.0.1:8443", "10.0.0.5"))
    }

    @Test
    fun `another host or empty input is left alone`() {
        assertEquals("https://example.com/x", applyBrowserHost("https://example.com/x", "10.0.0.5"))
        assertEquals("", applyBrowserHost("  ", "10.0.0.5"))
    }
}

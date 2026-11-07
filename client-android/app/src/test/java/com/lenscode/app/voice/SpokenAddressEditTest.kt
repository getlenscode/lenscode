package com.lenscode.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenAddressEditTest {
    private fun edit(cur: String, say: String) = applySpokenAddressEdit(cur, say)

    @Test
    fun `a whole address replaces the field`() {
        assertEquals("192.168.3.1", edit("10.0.0.1", "one nine two dot one six eight dot three dot one"))
        assertEquals("192.168.3.1", edit("", "one nine two dot one six eight dot three dot one"))
    }

    @Test
    fun `port is added or changed`() {
        assertEquals("192.168.3.1:8443", edit("192.168.3.1", "add port 8443"))
        assertEquals("192.168.3.1:9000", edit("192.168.3.1:8443", "actually port nine zero zero zero"))
        assertEquals("wss://h:8443", edit("wss://h", "add port eight four four three"))
    }

    @Test
    fun `scheme is added or swapped`() {
        assertEquals("http://192.168.3.1", edit("192.168.3.1", "add http"))
        assertEquals("http://h:3000", edit("https://h:3000", "http instead of https"))
        assertEquals("wss://h:8443", edit("ws://h:8443", "wss instead of ws"))
        assertEquals("wss://h", edit("ws://h", "w s s instead of w s"))
        assertEquals("http://h", edit("https://h", "change https to http"))
    }

    @Test
    fun `octets and digits`() {
        assertEquals("192.168.3.6", edit("192.168.3.1", "make the last digit 6"))
        assertEquals("192.168.3.16", edit("192.168.3.12", "make the last digit six"))
        assertEquals("192.168.5.1", edit("192.168.3.1", "make the last second digit of the IP 5"))
        assertEquals("192.168.3.20", edit("192.168.3.1", "make the last octet 20"))
        assertEquals("192.168.7.1", edit("192.168.3.1", "third octet seven"))
    }

    @Test
    fun `edits that cannot apply leave the address alone`() {
        assertEquals("192.168.3.1", edit("192.168.3.1", "make the last octet 999"))
        assertEquals("myhost.local", edit("myhost.local", "make the last digit 6"))
    }

    @Test
    fun `messy recognizer output`() {
        assertEquals("192.168.3.1:8443", edit("192.168.3.1", "Add port 8,443."))
        assertEquals("192.168.3.1:8443", edit("192.168.3.1", "add port 8443"))
        assertEquals("192.168.3.1:8443", edit("192.168.3.1", "port: 8443"))
        assertEquals("http://h:3000", edit("https://h:3000", "HTTP instead of HTTPS."))
        assertEquals("192.168.3.2", edit("192.168.3.1", "Make the last digit to."))
        assertEquals("192.168.3.4", edit("192.168.3.1", "make the last digit for"))
        assertEquals("192.168.3.6", edit("192.168.3.1", "  make   the last digit   6  "))
    }

    @Test
    fun `corrections chain one after another`() {
        var field = edit("", "one nine two dot one six eight dot three dot three")
        assertEquals("192.168.3.3", field)
        field = edit(field, "actually add port 8443")
        assertEquals("192.168.3.3:8443", field)
        field = edit(field, "add wss")
        assertEquals("wss://192.168.3.3:8443", field)
        field = edit(field, "make the last digit 6")
        assertEquals("wss://192.168.3.6:8443", field)
        field = edit(field, "second last digit 5")
        assertEquals("wss://192.168.5.6:8443", field)
        field = edit(field, "ws instead of wss")
        assertEquals("ws://192.168.5.6:8443", field)
    }

    @Test
    fun `reapplying to growing partials does not compound`() {
        val before = "192.168.3.1"
        assertEquals("192.168.3.1", edit(before, "add"))
        assertEquals("192.168.3.1:8", edit(before, "add port 8"))
        assertEquals("192.168.3.1:8443", edit(before, "add port 8 4 4 3"))
    }

    @Test
    fun `a path and a port survive scheme and octet edits`() {
        assertEquals("http://10.0.0.5:3000/app", edit("https://10.0.0.5:3000/app", "http instead of https"))
        assertEquals("http://10.0.0.9:3000/app", edit("http://10.0.0.5:3000/app", "make the last digit 9"))
    }

    // ── Browser address: path edits (paths = true) ──────────────────────

    private fun browser(cur: String, say: String) = applySpokenAddressEdit(cur, say, paths = true)

    @Test
    fun `add slash docs appends to the end of the address`() {
        assertEquals("http://192.168.3.1:8000/docs", browser("http://192.168.3.1:8000", "add slash docs to the end"))
        assertEquals("http://192.168.3.1:8000/docs", browser("http://192.168.3.1:8000", "Add slash docs."))
        assertEquals("http://h/docs", browser("http://h", "add docs to the end"))
    }

    @Test
    fun `appending keeps an existing path, a trailing slash and the query`() {
        assertEquals("http://h/api/docs", browser("http://h/api", "add slash docs"))
        assertEquals("http://h/docs", browser("http://h/", "add slash docs"))
        assertEquals("http://h/api/docs?x=1", browser("http://h/api?x=1", "add slash docs to the end"))
    }

    @Test
    fun `several segments and spoken symbols and digits`() {
        assertEquals("http://h/api/v1", browser("http://h", "add slash api slash v one"))
        assertEquals("http://h/files/index.html", browser("http://h", "add slash files slash index dot html to the end"))
        assertEquals("http://h/user/5", browser("http://h", "add slash user slash five to the end"))
    }

    @Test
    fun `go to slash and set the path replace it`() {
        assertEquals("http://h:8000/admin", browser("http://h:8000/docs", "go to slash admin"))
        assertEquals("http://h:8000/admin.html", browser("http://h:8000/docs", "set the path to slash admin dot html"))
        assertEquals("http://h:8000/a/b", browser("http://h:8000/docs?x=1", "change the path to slash a slash b"))
    }

    @Test
    fun `remove the path, the last segment, or go up a level`() {
        assertEquals("http://h:8000", browser("http://h:8000/docs/x?y=1", "remove the path"))
        assertEquals("http://h/api", browser("http://h/api/docs", "remove the last segment"))
        assertEquals("http://h/api", browser("http://h/api/docs", "go up one level"))
        assertEquals("http://h", browser("http://h/api", "delete the last part"))
    }

    @Test
    fun `remove the port`() {
        assertEquals("http://h/docs", browser("http://h:8000/docs", "remove the port"))
        assertEquals("http://h", browser("http://h:8000", "drop the port"))
    }

    @Test
    fun `scheme and port edits still work alongside the path`() {
        assertEquals("http://h:8000/docs", browser("https://h:8000/docs", "make it http"))
        assertEquals("https://h:8000/docs", browser("http://h:8000/docs", "make it https"))
        assertEquals("http://h:9000/docs", browser("http://h:8000/docs", "add port nine zero zero zero"))
    }

    @Test
    fun `partial results never compound or replace the address`() {
        val before = "http://h:8000"
        assertEquals(before, browser(before, "add"))
        assertEquals("http://h:8000/", browser(before, "add slash"))
        assertEquals("http://h:8000/docs", browser(before, "add slash docs"))
        assertEquals("http://h:8000/docs", browser(before, "add slash docs to the end"))
    }

    @Test
    fun `a whole new address still replaces it`() {
        assertEquals("example.com/docs", browser("http://h:8000", "example dot com slash docs"))
        assertEquals("192.168.3.1:3000", browser("http://h:8000", "one nine two dot one six eight dot three dot one colon three zero zero zero"))
    }

    @Test
    fun `path edits are ignored in the bridge address field`() {
        assertEquals("192.168.3.1", applySpokenAddressEdit("192.168.3.1", "add slash docs"))
    }
}

package com.ikoude.cnb2api.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

class WebDavTest {

    @Test
    fun targetNormalizesDirectoryAndRejectsBadUrls() {
        assertEquals("http://127.0.0.1:8088/dav/cnb2api-backup.json", WebDav.target("http://127.0.0.1:8088/dav/"))
        assertEquals("http://127.0.0.1:8088/a.json", WebDav.target("http://127.0.0.1:8088/a.json"))
        assertThrows(WebDavException::class.java) { WebDav.target("") }
        assertThrows(WebDavException::class.java) { WebDav.target("ftp://x/y") }
        assertThrows(WebDavException::class.java) { WebDav.target("https://x/y#a") }
        assertThrows(WebDavException::class.java) { WebDav.target("https://x/y?q=1") }
    }

    @Test
    fun putThenGetRoundTripAndBasicAuth() {
        val server = ServerSocket(0)
        var stored: String? = null
        var auth: String? = null
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine() ?: ""
                    if (requestLine.isBlank()) {
                        it.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        it.getOutputStream().flush()
                        return@use
                    }
                    var contentLength = 0
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val lower = line.lowercase()
                        if (lower.startsWith("authorization:")) auth = line.substringAfter(":").trim()
                        if (lower.startsWith("content-length:")) contentLength = line.substringAfter(":").trim().toInt()
                    }
                    val isPut = requestLine.startsWith("PUT")
                    if (isPut) {
                        val buf = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(buf, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        stored = String(buf, 0, read)
                    }
                    val out = it.getOutputStream()
                    if (!isPut && stored == null) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    } else if (isPut) {
                        out.write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    } else {
                        val data = stored!!.toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\n\r\n".toByteArray())
                        out.write(data)
                    }
                    out.flush()
                }
            }
        }
        try {
            val port = server.localPort
            val (url, bytes) = WebDav.put("http://127.0.0.1:$port/dav/", "u", "p", "{\"ok\":true}")
            assertTrue(url.endsWith("/dav/cnb2api-backup.json"))
            assertEquals(11, bytes)
            assertTrue(auth?.startsWith("Basic ") == true)
            val (content, gotUrl) = WebDav.get("http://127.0.0.1:$port/dav/", "u", "p")
            assertEquals("{\"ok\":true}", content)
            assertEquals(url, gotUrl)
        } finally {
            server.close()
            worker.join(1000)
        }
    }
}

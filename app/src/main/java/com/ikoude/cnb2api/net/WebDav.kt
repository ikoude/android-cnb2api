package com.ikoude.cnb2api.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

class WebDavException(message: String) : Exception(message)

/** WebDAV PUT/GET，语义与插件 lib/webdav.mjs 对齐。 */
object WebDav {
    private const val FILE_NAME = "cnb2api-backup.json"
    private const val MAX_BACKUP_BYTES = 16L * 1024 * 1024
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun target(baseUrl: String, fileName: String = FILE_NAME): String {
        val raw = baseUrl.trim()
        if (raw.isEmpty()) throw WebDavException("先填 WebDAV 地址")
        val url = try {
            java.net.URL(raw)
        } catch (e: Exception) {
            throw WebDavException("WebDAV 地址不是合法 URL")
        }
        if (url.protocol != "http" && url.protocol != "https") throw WebDavException("WebDAV 地址只支持 http / https")
        if (!url.ref.isNullOrBlank()) throw WebDavException("WebDAV 地址不要带 # 片段")
        if (!url.query.isNullOrBlank()) throw WebDavException("WebDAV 地址不要带 ? 查询参数")
        val host = url.host ?: throw WebDavException("WebDAV 地址缺少主机名")
        var path = url.path.orEmpty()
        if (path.endsWith("/")) path += fileName
        return java.net.URI(url.protocol, null, host, url.port, path, null, null).toString()
    }

    fun put(baseUrl: String, username: String, password: String, content: String): Pair<String, Int> {
        val target = target(baseUrl)
        val body = content.toRequestBody(jsonType)
        val builder = Request.Builder().url(target).put(body)
        if (username.isNotBlank()) builder.header("Authorization", basic(username, password))
        http.newCall(builder.build()).execute().use { res ->
            if (!res.isSuccessful) {
                val text = res.body?.string().orEmpty().replace(Regex("\\s+"), " ").trim().take(120)
                throw WebDavException("WebDAV 上传失败 · HTTP ${res.code}" + if (text.isNotEmpty()) " · $text" else "")
            }
        }
        return target to content.toByteArray(Charsets.UTF_8).size
    }

    fun get(baseUrl: String, username: String, password: String): Pair<String, String> {
        val target = target(baseUrl)
        val builder = Request.Builder().url(target).get().header("Accept", "application/json, text/plain, */*")
        if (username.isNotBlank()) builder.header("Authorization", basic(username, password))
        http.newCall(builder.build()).execute().use { res ->
            if (res.code == 404) throw WebDavException("WebDAV 上没有这个文件 · 检查地址与文件名是否一致")
            if (!res.isSuccessful) {
                val text = res.body?.string().orEmpty().replace(Regex("\\s+"), " ").trim().take(120)
                throw WebDavException("WebDAV 下载失败 · HTTP ${res.code}" + if (text.isNotEmpty()) " · $text" else "")
            }
            val body = res.body ?: return "" to target
            // 先看声明长度，无 Content-Length 时用限长读取，避免超大文件先被整体读进内存。
            val declared = body.contentLength()
            if (declared > MAX_BACKUP_BYTES) throw WebDavException("备份文件过大 · 超过 16MiB")
            val bytes = body.byteStream().use { readAtMost(it, MAX_BACKUP_BYTES) }
                ?: throw WebDavException("备份文件过大 · 超过 16MiB")
            return String(bytes, Charsets.UTF_8) to target
        }
    }

    /** 读取最多 max 字节；超过上限返回 null（不把超大响应整体载入内存）。 */
    private fun readAtMost(input: java.io.InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun basic(username: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
}

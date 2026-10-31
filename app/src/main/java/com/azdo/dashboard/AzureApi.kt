package com.azdo.dashboard

import android.util.Base64
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

class ApiFailure(val safeMessage: String) : Exception(safeMessage)
data class JsonPage(val values: JSONArray, val next: String?)

object AzureUrls {
    fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun api(org: String, project: String?, path: String, query: Map<String, String>): String {
        val base = "https://dev.azure.com/${segment(org)}/" + (project?.let { "${segment(it)}/" } ?: "")
        return base + "_apis/" + path + "?" +
            (mapOf("api-version" to "7.1") + query).entries.joinToString("&") { "${segment(it.key)}=${segment(it.value)}" }
    }
    fun isAzure(url: String, org: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host.equals("dev.azure.com", true) &&
            (uri.port == -1 || uri.port == 443) && uri.userInfo == null &&
            uri.rawPath.split('/').getOrNull(1) == segment(org) &&
            uri.normalize().rawPath == uri.rawPath &&
            !Regex("%(?:2f|5c|25)", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath) &&
            !uri.path.contains('\\') && !uri.path.any { it.isISOControl() } &&
            uri.path.split('/').none { it == "." || it == ".." }
    } catch (_: Exception) { false }
    fun isBlob(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && (uri.port == -1 || uri.port == 443) && uri.userInfo == null &&
            uri.host?.lowercase()?.let {
                it == "vsblob.vsassets.io" || it.endsWith(".vsblob.vsassets.io") ||
                    it.endsWith(".vsblob.visualstudio.com") ||
                    it.matches(Regex("[a-z0-9]{3,24}\\.blob\\.core\\.windows\\.net"))
            } == true
    } catch (_: Exception) { false }
    fun reportEntry(name: String): Boolean {
        if (name.startsWith("/") || name.contains('\\') || name.contains(':') ||
            name.any { it.isISOControl() } || name.split('/').any { it == ".." || it == "." } ||
            name.length > 512) return false
        return name.endsWith(".html", true) || name.endsWith(".htm", true) || name.endsWith(".md", true) ||
            name.endsWith(".markdown", true)
    }
}

class AzureApi(private val account: Account) {
    private val jsonLimit = 4 * 1024 * 1024
    private val reportLimit = 2 * 1024 * 1024
    private val archiveLimit = 20 * 1024 * 1024

    private suspend fun bytes(connection: HttpURLConnection, max: Int): ByteArray {
        val started = SystemClock.elapsedRealtime()
        if (connection.contentLengthLong > max) throw ApiFailure("Response is too large to preview.")
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() - started > 60_000) throw ApiFailure("The download timed out. Try again.")
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > max) throw ApiFailure("Response is too large to preview.")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
    private fun connection(url: String, authenticated: Boolean, accept: String = "application/json"): HttpURLConnection {
        if (authenticated && !AzureUrls.isAzure(url, account.organization)) throw ApiFailure("Unsupported download host.")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", accept)
            if (authenticated) setRequestProperty("Authorization", "Basic " +
                Base64.encodeToString(":${account.pat}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
    }
    private fun checkStatus(code: Int) {
        if (code in 200..299) return
        throw ApiFailure(when (code) {
            401, 403 -> "Access denied. Check the PAT and its read permissions."
            404 -> "The requested Azure DevOps resource was not found."
            429 -> "Azure DevOps is rate limiting requests. Try again later."
            in 300..399 -> "Authentication redirects are not supported. Check the organization and PAT."
            else -> "Azure DevOps request failed (HTTP $code)."
        })
    }
    suspend fun page(project: String?, path: String, query: Map<String, String> = emptyMap()): JsonPage =
        withContext(Dispatchers.IO) {
            val conn = connection(AzureUrls.api(account.organization, project, path, query), true)
            try {
                checkStatus(conn.responseCode)
                val json = JSONObject(String(bytes(conn, jsonLimit), Charsets.UTF_8))
                JsonPage(json.optJSONArray("value") ?: JSONArray(), conn.getHeaderField("x-ms-continuationtoken")?.takeIf { it.isNotBlank() })
            } finally { conn.disconnect() }
        }
    suspend fun projects(token: String?) = page(null, "projects", mapOf("\$top" to "100") + tokenQuery(token))
    suspend fun definitions(project: String, token: String?) =
        page(project, "build/definitions", mapOf("\$top" to "100") + tokenQuery(token))
    suspend fun runs(project: String, definition: Int, token: String?, latest: Boolean = false) =
        page(project, "build/builds", mapOf("definitions" to definition.toString(), "\$top" to if (latest) "1" else "30",
            "queryOrder" to "queueTimeDescending") + tokenQuery(token))
    suspend fun artifacts(project: String, build: Int) = page(project, "build/builds/$build/artifacts")
    private fun tokenQuery(token: String?) = token?.let { mapOf("continuationToken" to it) } ?: emptyMap()

    suspend fun tests(project: String, build: Int, buildUri: String = ""): JsonPage = withContext(Dispatchers.IO) {
        val results = JSONArray()
        var resultBytes = 0L
        var skipRuns = 0
        var finished = false
        var runPages = 0
        while (!finished && runPages++ < 100) {
            val runs = page(project, "test/runs", mapOf("buildUri" to buildUri.ifBlank { "vstfs:///Build/Build/$build" },
                "\$top" to "100", "\$skip" to skipRuns.toString()))
            for (i in 0 until runs.values.length()) {
                val runId = runs.values.getJSONObject(i).getInt("id")
                var skip = 0
                var resultFinished = false
                var resultPages = 0
                while (!resultFinished && resultPages++ < 1000) {
                    val batch = page(project, "test/Runs/$runId/results", mapOf("\$top" to "100", "\$skip" to skip.toString()))
                    resultBytes += batch.values.toString().toByteArray(Charsets.UTF_8).size
                    if (resultBytes > archiveLimit) throw ApiFailure("Test results exceed the 20 MiB preview limit.")
                    for (j in 0 until batch.values.length()) {
                        if (results.length() >= 20_000) throw ApiFailure("This build exceeds the 20,000-test preview limit.")
                        results.put(batch.values.getJSONObject(j))
                    }
                    skip += batch.values.length()
                    resultFinished = batch.values.length() == 0
                }
                if (!resultFinished) throw ApiFailure("Test pagination exceeded the safety limit.")
            }
            skipRuns += runs.values.length()
            finished = runs.values.length() == 0
        }
        if (!finished) throw ApiFailure("Test run pagination exceeded the safety limit.")
        JsonPage(results, null)
    }

    suspend fun reports(project: String, build: Int, artifact: Artifact): List<Report> = withContext(Dispatchers.IO) {
        val archive = artifact.type.equals("Container", true) || artifact.type.equals("PipelineArtifact", true)
        val initialUrl = if (archive) AzureUrls.api(account.organization, project, "build/builds/$build/artifacts",
            mapOf("artifactName" to artifact.name)) else artifact.url
        if (!AzureUrls.isAzure(initialUrl, account.organization)) throw ApiFailure("Only this organization's Azure DevOps report URLs can be opened.")
        var url = initialUrl
        var auth = true
        var payload: ByteArray? = null
        var contentType = ""
        for (hop in 0..3) {
            coroutineContext.ensureActive()
            val conn = connection(url, auth, if (archive) "application/zip" else "*/*")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw ApiFailure("Invalid download redirect.")
                    val next = URI(url).resolve(location).toString()
                    // Once redirected, a download is never given the PAT again.
                    if (!AzureUrls.isBlob(next)) throw ApiFailure("Unsupported download redirect. No credentials were forwarded.")
                    url = next
                    auth = false
                } else {
                    checkStatus(code)
                    contentType = conn.contentType.orEmpty().lowercase()
                    payload = bytes(conn, archiveLimit)
                    break
                }
            } finally { conn.disconnect() }
        }
        val data = payload ?: throw ApiFailure("Too many download redirects.")
        if (data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4b.toByte()) {
            val previews = mutableListOf<Report>()
            var total = 0L
            var entries = 0
            ZipInputStream(data.inputStream()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (++entries > 1000) throw ApiFailure("Archive contains too many entries.")
                    val eligible = !entry.isDirectory && AzureUrls.reportEntry(entry.name)
                    val output = if (eligible) ByteArrayOutputStream() else null
                    val buffer = ByteArray(8192)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > archiveLimit || (output?.size() ?: 0) + count > reportLimit && eligible)
                            throw ApiFailure("Archive exceeds safe preview size limits.")
                        output?.write(buffer, 0, count)
                    }
                    if (output != null) {
                        if (previews.size >= 30) throw ApiFailure("Archive contains more than 30 reports.")
                        previews += Report(entry.name, output.toString("UTF-8"), !entry.name.endsWith(".md", true) && !entry.name.endsWith(".markdown", true))
                    }
                    zip.closeEntry()
                }
            }
            if (previews.isEmpty()) throw ApiFailure("No HTML or Markdown reports were found in this ZIP artifact.")
            previews
        } else {
            if (data.size > reportLimit) throw ApiFailure("Report exceeds the 2 MiB preview limit.")
            val path = URI(initialUrl).path.orEmpty()
            val html = contentType.startsWith("text/html") || path.endsWith(".html", true) || path.endsWith(".htm", true)
            val markdown = contentType.startsWith("text/markdown") || path.endsWith(".md", true) || path.endsWith(".markdown", true)
            if (!html && !markdown) throw ApiFailure("Only HTML, Markdown, and ZIP report artifacts are supported.")
            listOf(Report(artifact.name, String(data, Charsets.UTF_8), html))
        }
    }
}

package app.cmuxpocket.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val latestVersion: String,
        val currentVersion: String,
        val downloadUrl: String,
        val releaseUrl: String
    ) : UpdateCheckResult

    data class UpToDate(
        val currentVersion: String
    ) : UpdateCheckResult

    data class Error(
        val message: String
    ) : UpdateCheckResult
}

object ReleaseUpdateChecker {

    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/shiquda/cmux-pocket/releases/latest"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersionName: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "cmux-pocket-android")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext UpdateCheckResult.Error("HTTP ${response.code}: ${response.message}")
            }

            val bodyString = response.body?.string() ?: return@withContext UpdateCheckResult.Error("Empty response from GitHub")
            val releaseObj = json.parseToJsonElement(bodyString).jsonObject

            val tagName = releaseObj["tag_name"]?.jsonPrimitive?.content ?: ""
            val htmlUrl = releaseObj["html_url"]?.jsonPrimitive?.content ?: "https://github.com/shiquda/cmux-pocket/releases"

            // Look for .apk asset download url, or fall back to release page html_url
            val assetsArray = releaseObj["assets"]?.jsonArray
            val apkDownloadUrl = assetsArray?.firstNotNullOfOrNull { element ->
                val assetObj = element.jsonObject
                val name = assetObj["name"]?.jsonPrimitive?.content.orEmpty()
                if (name.endsWith(".apk", ignoreCase = true)) {
                    assetObj["browser_download_url"]?.jsonPrimitive?.content
                } else null
            } ?: htmlUrl

            if (isNewerVersion(remoteTag = tagName, localVersion = currentVersionName)) {
                UpdateCheckResult.UpdateAvailable(
                    latestVersion = tagName,
                    currentVersion = currentVersionName,
                    downloadUrl = apkDownloadUrl,
                    releaseUrl = htmlUrl
                )
            } else {
                UpdateCheckResult.UpToDate(currentVersion = currentVersionName)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Failed to check for updates")
        }
    }

    fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V")
        val cleanLocal = localVersion.trim().removePrefix("v").removePrefix("V")

        if (cleanRemote.isBlank()) return false
        if (cleanLocal.isBlank()) return true

        val remoteParts = cleanRemote.split(".").mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
        val localParts = cleanLocal.split(".").mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }

        return false
    }
}

package com.dracula.taskbite

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class UpdateCandidate(val versionCode: Long, val versionName: String, val minSdk: Int,
    val size: Long, val sha256: String, val apkUrl: String, val releaseUrl: String, val notes: String) {
    init {
        require(versionCode in 1..Int.MAX_VALUE.toLong())
        require(versionName.length in 1..64 && versionName.none { it.isISOControl() })
        require(minSdk in 1..1000 && size in 1..UpdateRelease.MAX_APK_BYTES)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(UpdateRelease.isReleaseAsset(apkUrl) && apkUrl.endsWith(".apk"))
        require(releaseUrl.startsWith("${UpdateRelease.REPOSITORY}/releases/tag/") && UpdateRelease.isTrustedUrl(releaseUrl))
        require(notes.length <= 4000)
    }
    val key get() = "$versionCode-$sha256"
    fun saved() = JSONObject().put("versionCode", versionCode).put("versionName", versionName).put("minSdk", minSdk)
        .put("size", size).put("sha256", sha256).put("apkUrl", apkUrl).put("releaseUrl", releaseUrl).put("notes", notes).toString()
    companion object {
        fun restore(raw: String?): UpdateCandidate? = raw?.let { runCatching {
            val j = JSONObject(it)
            UpdateCandidate(j.getLong("versionCode"), j.getString("versionName"), j.getInt("minSdk"), j.getLong("size"),
                j.getString("sha256"), j.getString("apkUrl"), j.getString("releaseUrl"), j.getString("notes"))
        }.getOrNull() }
    }
}

object UpdateRelease {
    const val REPOSITORY = "https://github.com/dracula74644/TaskBite"
    const val API = "https://api.github.com/repos/dracula74644/TaskBite/releases/latest"
    const val MANIFEST = "taskbite-update.json"
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    fun isTrustedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
    fun isReleaseAsset(url: String): Boolean = isTrustedUrl(url) && url.startsWith("$REPOSITORY/releases/download/") &&
        URI(url).rawQuery == null && URI(url).normalize() == URI(url) && !URI(url).rawPath.contains('%')

    private fun assets(release: JSONObject) = release.getJSONArray("assets").let { list ->
        (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("state") == "uploaded" }
    }
    fun manifestUrl(release: JSONObject): String {
        require(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "Only stable published releases can update Bite." }
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(release.getString("html_url") == "$REPOSITORY/releases/tag/$tag")
        val manifest = assets(release).single { it.getString("name") == MANIFEST }
        require(manifest.getLong("size") in 1..65_536)
        val url = manifest.getString("browser_download_url")
        require(url == "$REPOSITORY/releases/download/$tag/$MANIFEST" && isReleaseAsset(url))
        return url
    }
    fun parse(release: JSONObject, raw: String): UpdateCandidate {
        manifestUrl(release)
        val j = JSONObject(raw)
        require(j.getInt("formatVersion") == 1 && j.getString("packageName") == BuildConfig.APPLICATION_ID)
        val name = j.getString("apk")
        require(name.matches(Regex("TaskBite-[A-Za-z0-9._-]+\\.apk")))
        val asset = assets(release).single { it.getString("name") == name }
        val url = asset.getString("browser_download_url")
        require(url == "$REPOSITORY/releases/download/${release.getString("tag_name")}/$name")
        require(j.getLong("size") == asset.getLong("size"))
        val hash = j.getString("sha256").lowercase()
        val githubDigest = asset.optString("digest").takeUnless { it.isBlank() || it == "null" }
        require(githubDigest == null || githubDigest == "sha256:$hash")
        return UpdateCandidate(j.getLong("versionCode"), j.getString("versionName"), j.getInt("minSdk"), j.getLong("size"), hash,
            url, release.getString("html_url"), j.optString("notes", release.optString("body")).take(4000))
    }
    suspend fun latest(transport: UpdateTransport = GithubUpdateTransport): UpdateCandidate? {
        val raw = try { transport.read(API, 256 * 1024) } catch (e: UpdateHttpException) { if (e.status == 404) return null else throw e }
        val release = JSONObject(raw.toString(Charsets.UTF_8))
        return parse(release, transport.read(manifestUrl(release), 65_536).toString(Charsets.UTF_8))
    }
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verifyBytes(file: File, candidate: UpdateCandidate) {
        require(file.isFile && file.length() == candidate.size && sha256(file) == candidate.sha256) {
            "The download is incomplete or damaged. Download it again."
        }
    }
    @Suppress("DEPRECATION")
    fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) } }.toSet()
    }
    @Suppress("DEPRECATION")
    fun verifyIdentity(candidate: UpdateCandidate, installed: PackageInfo, archive: PackageInfo, sdk: Int = Build.VERSION.SDK_INT) {
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(installed.packageName == BuildConfig.APPLICATION_ID && archive.packageName == installed.packageName) { "This file is not a Bite update." }
        require(archiveCode == candidate.versionCode && archiveCode > installedCode && archive.versionName == candidate.versionName) { "This file is not the newer version shown here." }
        require(archive.applicationInfo?.minSdkVersion == candidate.minSdk && candidate.minSdk <= sdk) { "This update needs a newer Android version." }
        val trusted = signers(installed)
        require(trusted.isNotEmpty() && signers(archive) == trusted) { "This update was signed by a different developer. Installation was stopped." }
    }
    @Suppress("DEPRECATION")
    fun verifyApk(context: Context, file: File, candidate: UpdateCandidate) {
        verifyBytes(file, candidate)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = manager.getPackageArchiveInfo(file.path, flags) ?: error("Android couldn't read this update. Download it again.")
        verifyIdentity(candidate, installed, archive)
    }
}

class UpdateHttpException(val status: Int) : IOException("Update server returned HTTP $status")
interface UpdateTransport {
    suspend fun read(url: String, maxBytes: Int): ByteArray
    suspend fun download(url: String, target: File, size: Long, progress: suspend (Int) -> Unit)
}

object GithubUpdateTransport : UpdateTransport {
    private suspend fun <T> request(url: String, accept: String, block: suspend (HttpURLConnection) -> T): T = withContext(Dispatchers.IO) {
        var next = url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(UpdateRelease.isTrustedUrl(next)) { "The update server redirected to an untrusted address." }
            val connection = URL(next).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "TaskBite/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept", accept)
                when (val status = connection.responseCode) {
                    200 -> return@withContext block(connection)
                    301, 302, 303, 307, 308 -> next = URL(URL(next), connection.getHeaderField("Location") ?: error("Missing update redirect")).toString()
                    else -> throw UpdateHttpException(status)
                }
            } finally { connection.disconnect() }
        }
        error("Too many update redirects.")
    }
    internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(output.size().toLong() + count <= maxBytes) { "The update response is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    override suspend fun read(url: String, maxBytes: Int) = request(url, "application/json") { connection ->
        require(connection.contentLengthLong <= maxBytes)
        connection.inputStream.use { readBounded(it, maxBytes) }
    }
    override suspend fun download(url: String, target: File, size: Long, progress: suspend (Int) -> Unit) {
        require(size in 1..UpdateRelease.MAX_APK_BYTES)
        request(url, "application/octet-stream") { connection ->
            require(connection.contentLengthLong == -1L || connection.contentLengthLong == size) { "The update size changed. Check for updates again." }
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L; var reported = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    total += count
                    require(total <= size) { "The update is larger than expected." }
                    output.write(buffer, 0, count)
                    val percent = (total * 100 / size).toInt()
                    if (percent != reported) { progress(percent); reported = percent }
                }
                require(total == size) { "The download stopped early. Try again." }
            } }
        }
    }
}

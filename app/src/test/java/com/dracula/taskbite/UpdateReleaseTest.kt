package com.dracula.taskbite

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

internal object UpdateFixture {
    val bytes = "update fixture bytes".toByteArray()
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun candidate(code: Long = BuildConfig.VERSION_CODE + 1L) = UpdateCandidate(code, "9.0", 26, bytes.size.toLong(), hash,
        "${UpdateRelease.REPOSITORY}/releases/download/v9.0/TaskBite-9.0.apk", "${UpdateRelease.REPOSITORY}/releases/tag/v9.0", "A small improvement.")
    fun release() = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v9.0")
        .put("html_url", "${UpdateRelease.REPOSITORY}/releases/tag/v9.0")
        .put("assets", JSONArray().put(asset(UpdateRelease.MANIFEST, 600)).put(asset("TaskBite-9.0.apk", bytes.size).put("digest", "sha256:$hash")))
    private fun asset(name: String, size: Int) = JSONObject().put("name", name).put("size", size).put("state", "uploaded")
        .put("browser_download_url", "${UpdateRelease.REPOSITORY}/releases/download/v9.0/$name")
    fun manifest() = JSONObject().put("formatVersion", 1).put("packageName", BuildConfig.APPLICATION_ID)
        .put("versionCode", BuildConfig.VERSION_CODE + 1).put("versionName", "9.0").put("minSdk", 26)
        .put("apk", "TaskBite-9.0.apk").put("size", bytes.size).put("sha256", hash).put("notes", "A small improvement.")
    @Suppress("DEPRECATION")
    fun info(code: Long, certificate: Byte = 1) = PackageInfo().apply {
        packageName = BuildConfig.APPLICATION_ID; versionName = "9.0"; versionCode = code.toInt()
        if (Build.VERSION.SDK_INT >= 28) longVersionCode = code
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 26 }
        signatures = arrayOf(Signature(byteArrayOf(certificate, 2, 3)))
        if (Build.VERSION.SDK_INT >= 28) signingInfo = SigningInfo().also { shadowOf(it).setSignatures(signatures) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class UpdateReleaseTest {
    @Test fun stableReleaseUsesExactRepositoryAssetsAndRoundTripsSavedMetadata() {
        val candidate = UpdateRelease.parse(UpdateFixture.release(), UpdateFixture.manifest().toString())
        assertEquals(UpdateFixture.candidate(), candidate)
        assertEquals(candidate, UpdateCandidate.restore(candidate.saved()))
        assertNull(UpdateCandidate.restore("not json"))
    }
    @Test fun draftPrereleaseMissingManifestAndWrongRepositoryAreRejected() {
        for (field in listOf("draft", "prerelease")) assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.manifestUrl(UpdateFixture.release().put(field, true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.manifestUrl(UpdateFixture.release().put("html_url", "https://github.com/someone/TaskBite/releases/tag/v9.0"))
        }
        assertThrows(NoSuchElementException::class.java) {
            UpdateRelease.manifestUrl(UpdateFixture.release().put("assets", JSONArray()))
        }
    }
    @Test fun wrongPackageSizeDigestAndUnsafeFilenameAreRejected() {
        val changes = listOf("packageName" to "another.app", "size" to 1, "sha256" to "f".repeat(64), "apk" to "../TaskBite.apk")
        changes.forEach { (key, value) -> assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.parse(UpdateFixture.release(), UpdateFixture.manifest().put(key, value).toString())
        } }
    }
    @Test fun redirectsCannotUseHttpCredentialsForeignHostsOrReleasePathTraversal() {
        listOf("http://github.com/file", "https://github.com.evil.test/file", "https://user@github.com/file",
            "https://github.com:444/file", "https://localhost/file").forEach { assertFalse(it, UpdateRelease.isTrustedUrl(it)) }
        assertTrue(UpdateRelease.isTrustedUrl("https://release-assets.githubusercontent.com/asset?token=temporary"))
        assertFalse(UpdateRelease.isReleaseAsset("${UpdateRelease.REPOSITORY}/releases/download/v9.0/../TaskBite.apk"))
        assertFalse(UpdateRelease.isReleaseAsset("${UpdateRelease.REPOSITORY}/releases/download/v9.0/%2e%2e/TaskBite.apk"))
    }
    @Test fun boundedResponsesAndTruncatedOrTamperedDownloadsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { GithubUpdateTransport.readBounded(ByteArrayInputStream(ByteArray(20)), 19) }
        assertArrayEquals(ByteArray(20), GithubUpdateTransport.readBounded(ByteArrayInputStream(ByteArray(20)), 20))
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "fixture.apk")
        file.writeBytes(UpdateFixture.bytes)
        UpdateRelease.verifyBytes(file, UpdateFixture.candidate())
        file.writeBytes(ByteArray(UpdateFixture.bytes.size))
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyBytes(file, UpdateFixture.candidate()) }
        file.writeBytes(UpdateFixture.bytes.dropLast(1).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyBytes(file, UpdateFixture.candidate()) }
    }
    @Test fun identityRequiresNewerVersionSamePackageSignerAndCompatibleAndroid() {
        val candidate = UpdateFixture.candidate()
        val own = UpdateFixture.info(BuildConfig.VERSION_CODE.toLong())
        val next = UpdateFixture.info(candidate.versionCode)
        UpdateRelease.verifyIdentity(candidate, own, next)
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyIdentity(candidate, own, UpdateFixture.info(candidate.versionCode, 9)) }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyIdentity(candidate, own, next.apply { packageName = "different.app" }) }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyIdentity(candidate, own, UpdateFixture.info(BuildConfig.VERSION_CODE.toLong())) }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyIdentity(candidate, own, UpdateFixture.info(candidate.versionCode), sdk = 25) }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyIdentity(candidate, own, UpdateFixture.info(candidate.versionCode).apply { versionName = "wrong" }) }
    }
    @Test @Config(sdk = [26]) fun androidEightAlsoRequiresTheOriginalSigningCertificate() {
        val candidate = UpdateFixture.candidate()
        val own = UpdateFixture.info(BuildConfig.VERSION_CODE.toLong())
        UpdateRelease.verifyIdentity(candidate, own, UpdateFixture.info(candidate.versionCode))
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyIdentity(candidate, own, UpdateFixture.info(candidate.versionCode, 7)) }
    }
}

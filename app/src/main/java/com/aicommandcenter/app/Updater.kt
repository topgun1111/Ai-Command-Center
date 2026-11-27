package com.aicommandcenter.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.MessageDigest

class Updater(private val context: Context) {
    private val prefs = context.getSharedPreferences("apk_updates", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val repo = BuildConfig.UPDATE_REPO
    data class Asset(val name: String, val url: String, val digest: String, val size: Long)
    data class Release(val tag: String, val full: Asset, val delta: Asset?, val preferDelta: Boolean)
    private fun baseApk() = File(context.applicationInfo.sourceDir)
    private fun onWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !cm.isActiveNetworkMetered
    }
    data class Status(val message: String, val ready: Boolean = false, val pending: Boolean = false)
    val id get() = prefs.getLong("id", -1L)
    val configured get() = repo.isNotBlank()
    val releasesUrl get() = "https://github.com/$repo/releases"

    fun check(): Release? {
        require(configured) { "This build has no update repository configured." }
        val connection = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "AICommandCenter/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            require(status == 200) { if (status == 404) "No public release found." else "GitHub returned HTTP $status. Try again later." }
            val raw = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(4096)
                while (true) { val n = reader.read(buffer); if (n < 0) break; require(result.length + n <= 2_000_000) { "Release response too large" }; result.append(buffer, 0, n) }
                result.toString()
            }
            val release = JSONObject(raw)
            require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "Not a stable release" }
            val tag = release.getString("tag_name")
            require(UpdateRules.code(tag) != null) { "Unrecognized release version: $tag" }
            if (!UpdateRules.newer(tag, BuildConfig.VERSION_CODE)) return null
            val assets = release.getJSONArray("assets")
            fun asset(name: String): Asset? {
                val obj = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.optString("name") == name } ?: return null
                val url = obj.optString("browser_download_url"); val digest = obj.optString("digest"); val size = obj.optLong("size", -1)
                if (url != UpdateRules.downloadUrl(repo, tag, name) || !UpdateRules.validDigest(digest) || size !in 1..UpdateRules.MAX_APK_BYTES || obj.optString("state") != "uploaded") return null
                return Asset(name, url, digest, size)
            }
            val full = asset(UpdateRules.assetName(tag)) ?: error("Expected signed APK missing or invalid")
            // Split APK installs cannot be reconstructed from sourceDir alone.
            val delta = if (context.applicationInfo.splitSourceDirs.isNullOrEmpty()) {
                runCatching { asset(UpdateRules.deltaName(tag, ApkDelta.digest(baseApk()))) }.getOrNull()?.takeIf { UpdateRules.usefulDelta(it.size, full.size) }
            } else null
            return Release(tag, full, delta, !onWifi() && delta != null)
        } finally { connection.disconnect() }
    }

    fun start(release: Release, useDelta: Boolean = release.preferDelta) {
        require(id < 0) { "Cancel the previous download first" }
        val asset = if (useDelta) requireNotNull(release.delta) else release.full
        require(UpdateRules.newer(release.tag, BuildConfig.VERSION_CODE) && asset.url == UpdateRules.downloadUrl(repo, release.tag, asset.name) && UpdateRules.validDigest(asset.digest))
        if (useDelta) require(UpdateRules.usefulDelta(asset.size, release.full.size) && asset.name == UpdateRules.deltaName(release.tag, ApkDelta.digest(baseApk()))) { "Patch does not match installed APK" }
        val request = DownloadManager.Request(Uri.parse(asset.url)).setTitle("AI Command Center ${release.tag}")
            .setDescription(if (useDelta) "Downloading partial update" else "Downloading signed application update")
            .setMimeType(if (useDelta) "application/octet-stream" else "application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val downloadId = manager.enqueue(request)
        if (!prefs.edit().putLong("id", downloadId).putString("tag", release.tag)
                .putString("digest", release.full.digest).putLong("size", release.full.size)
                .putBoolean("delta", useDelta).putString("download_digest", asset.digest).putLong("download_size", asset.size).commit()) {
            manager.remove(downloadId); error("Could not persist download state")
        }
    }

    fun status(): Status {
        if (id < 0) return Status("No update download in progress")
        if (!UpdateRules.newer(prefs.getString("tag", "").orEmpty(), BuildConfig.VERSION_CODE)) { cancel(); return Status("Installed version is up to date") }
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return Status("Download no longer available. Cancel and check again.")
            fun get(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            return when (get(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_SUCCESSFUL -> Status(if (prefs.getBoolean("delta", false)) "Partial download complete. Reconstruct, verify and install." else "Download complete. Verify and install.", ready = true)
                DownloadManager.STATUS_FAILED -> Status("Download failed (reason ${get(DownloadManager.COLUMN_REASON)}). Cancel and retry.")
                else -> {
                    val total = get(DownloadManager.COLUMN_TOTAL_SIZE_BYTES); val done = get(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    Status(if (total > 0) "Downloading ${if (prefs.getBoolean("delta", false)) "partial update" else "full APK"}: ${(100L * done / total).coerceIn(0,100)}%" else "Downloading / waiting for connection", pending = true)
                }
            }
        }
    }

    fun cancel() {
        if (id >= 0) manager.remove(id)
        prefs.edit().clear().commit()
        File(context.cacheDir, "verified_updates").deleteRecursively()
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return certs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Suppress("DEPRECATION")
    fun verify(): File {
        require(status().ready) { "Download is not complete" }
        val tag = prefs.getString("tag", "").orEmpty()
        val code = UpdateRules.code(tag) ?: error("Invalid saved version")
        val size = prefs.getLong("size", -1)
        require(size in 1..UpdateRules.MAX_APK_BYTES)
        val dir = File(context.cacheDir, "verified_updates"); check(dir.isDirectory || dir.mkdirs())
        val file = File(dir, "update.apk")
        val isDelta = prefs.getBoolean("delta", false)
        val downloaded = if (isDelta) File(dir, "download.delta") else file
        val downloadSize = prefs.getLong("download_size", size)
        val downloadDigest = prefs.getString("download_digest", prefs.getString("digest", ""))
        require(downloadSize in 1..UpdateRules.MAX_APK_BYTES && (!isDelta || UpdateRules.usefulDelta(downloadSize, size)))
        val hash = MessageDigest.getInstance("SHA-256")
        try {
            var bytes = 0L
            manager.openDownloadedFile(id).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                    downloaded.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= downloadSize) { "Download larger than advertised" }; hash.update(buffer, 0, n); out.write(buffer, 0, n) }
                    }
                }
            }
            val digest = "sha256:" + hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(bytes == downloadSize && digest == downloadDigest) { "Download checksum or size mismatch" }
            if (isDelta) {
                ApkDelta.apply(baseApk(), downloaded, file, size)
                require(file.length() == size && "sha256:" + ApkDelta.digest(file) == prefs.getString("digest", "")) { "Reconstructed APK checksum mismatch" }
            }
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Unreadable APK")
            val installed = pm.getPackageInfo(context.packageName, flags)
            val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            require(archive.packageName == context.packageName && archiveCode == code.toLong() && archiveCode > BuildConfig.VERSION_CODE) { "Wrong package or version" }
            val expected = signatures(installed)
            require(expected.isNotEmpty() && signatures(archive) == expected) { "APK signing key differs from installed app" }
            return file
        } catch (e: Exception) {
            file.delete()
            if (isDelta) throw IllegalStateException("Partial update failed: ${e.message}. Cancel download and choose Full APK on the next check.", e)
            throw e
        } finally { if (isDelta) downloaded.delete() }
    }

    fun requestInstallPermission(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
    }
    fun install(activity: Activity, file: File) {
        require(context.packageManager.canRequestPackageInstalls()) { "Allow installation from this app first" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

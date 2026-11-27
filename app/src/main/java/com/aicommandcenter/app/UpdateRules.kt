package com.aicommandcenter.app

object UpdateRules {
    private val tagPattern = Regex("v1\\.[0-9]+\\.([1-9][0-9]{0,9})-[0-9a-f]{7}")
    fun code(tag: String): Int? = tagPattern.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..2100000000 }
    fun newer(tag: String, installed: Int): Boolean = (code(tag) ?: 0) > installed
    fun assetName(tag: String) = "AICommandCenter-$tag.apk"
    fun assetUrl(repo: String, tag: String) = "https://github.com/$repo/releases/download/$tag/${assetName(tag)}"
    fun deltaName(tag: String, baseHash: String) = "AICommandCenter-$tag-from-$baseHash.delta"
    fun downloadUrl(repo: String, tag: String, name: String) = "https://github.com/$repo/releases/download/$tag/$name"
    fun usefulDelta(patch: Long, full: Long) = full in 1..MAX_APK_BYTES && patch in 49..MAX_APK_BYTES && patch * 10 < full * 9
    fun validDigest(value: String) = value.matches(Regex("sha256:[0-9a-f]{64}"))
    const val MAX_APK_BYTES = 200L * 1024 * 1024
}

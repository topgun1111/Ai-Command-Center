package com.aicommandcenter.app

fun main() {
    check(UpdateRules.code("v1.1.1001-abcdef0") == 1001)
    check(UpdateRules.newer("v1.1.1002-abcdef0", 1001))
    check(!UpdateRules.newer("v1.1.1001-abcdef0", 1001))
    check(!UpdateRules.newer("v1.1.999-abcdef0", 1001))
    for (tag in listOf("", "v1.1.0-abcdef0", "v1.1.01-abcdef0", "v1.1.1002-abcdef", "v1.1.2147483648-abcdef0", "v1.1.2100000001-abcdef0", "v1.1.-1-abcdef0", "v1.1.1002-abcdef0/evil", "v1.1.1002-ABCDEF0")) check(UpdateRules.code(tag) == null) { tag }
    check(UpdateRules.code("v1.1.2100000000-abcdef0") == 2100000000)
    check(UpdateRules.assetName("v1.1.1001-abcdef0") == "AICommandCenter-v1.1.1001-abcdef0.apk")
    check(UpdateRules.assetUrl("owner/repo", "v1.1.1001-abcdef0") == "https://github.com/owner/repo/releases/download/v1.1.1001-abcdef0/AICommandCenter-v1.1.1001-abcdef0.apk")
    check(UpdateRules.validDigest("sha256:" + "a".repeat(64)))
    for (digest in listOf("", "sha1:" + "a".repeat(64), "sha256:" + "a".repeat(63), "sha256:" + "z".repeat(64))) check(!UpdateRules.validDigest(digest))
    println("21 update rule checks passed")
}

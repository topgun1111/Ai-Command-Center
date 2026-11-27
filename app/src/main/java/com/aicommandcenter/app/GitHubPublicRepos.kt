package com.aicommandcenter.app

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.Locale

object GitHubPublicRepos {
    data class Repository(val fullName:String,val description:String,val archived:Boolean,val fork:Boolean)
    data class Result(val repositories:List<Repository>,val truncated:Boolean)
    fun fetch(owner:String):Result {
        require(GitHubRepoRules.normalizeOwner(owner)==owner) { "Invalid GitHub username" }
        val result=linkedMapOf<String,Repository>()
        for(page in 1..10) {
            if(Thread.currentThread().isInterrupted)throw IOException("Repository lookup cancelled")
            val address="https://api.github.com/users/$owner/repos?type=owner&sort=updated&direction=desc&per_page=100&page=$page"
            val c=URL(address).openConnection() as HttpsURLConnection
            c.instanceFollowRedirects=false
            c.connectTimeout=12000; c.readTimeout=15000
            c.setRequestProperty("Accept","application/vnd.github+json")
            c.setRequestProperty("User-Agent","AI-Command-Center")
            c.setRequestProperty("X-GitHub-Api-Version","2022-11-28")
            try {
                when(val status=c.responseCode) {
                    200 -> Unit
                    404 -> throw IOException("GitHub user not found")
                    403,429 -> throw IOException("GitHub rate limit or access restriction. Try again later.")
                    else -> throw IOException("GitHub returned HTTP $status")
                }
                val data=c.inputStream.use { input ->
                    val out=ByteArrayOutputStream(); val buffer=ByteArray(8192)
                    while(true) {
                        if(Thread.currentThread().isInterrupted)throw IOException("Repository lookup cancelled")
                        val count=input.read(buffer); if(count<0)break
                        if(out.size()+count>4_000_000)throw IOException("Repository response too large")
                        out.write(buffer,0,count)
                    }
                    JSONArray(out.toString("UTF-8"))
                }
                for(i in 0 until data.length()) {
                    val item=data.getJSONObject(i); val name=item.optString("full_name")
                    if(item.optBoolean("private",true)||!CommandEngine.validRepo(name)||!name.substringBefore('/').equals(owner,true))continue
                    result[name.lowercase(Locale.ROOT)]=Repository(name,if(item.isNull("description"))"" else item.optString("description"),item.optBoolean("archived"),item.optBoolean("fork"))
                }
                // Follow GitHub's pagination indication, not arbitrary redirect URLs.
                val more=c.getHeaderField("Link")?.contains("rel=\"next\"")==true
                if(!more)return Result(result.values.toList(),false)
                if(page==10)return Result(result.values.toList(),true)
            } finally { c.disconnect() }
        }
        return Result(result.values.toList(),false)
    }
}

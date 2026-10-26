package com.ivyea.weiguang.core

import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

// 自己的曲库服务器：Navidrome（及其他 Subsonic 兼容服务）、Jellyfin/Emby、WebDAV 网盘。
// 这些歌由微光自己放（service/WgPlayer）；账号密码只存在这台手机里
data class Server(
    val id: String, val kind: String, val name: String, val url: String,
    val user: String, val pass: String, val token: String = "", val uid: String = "",
)

// 服务器上的一首歌：loc=「服务器id:条目id」，放的时候换成播放地址
data class Item(val loc: String, val title: String, val artist: String, val durMs: Long, val cover: String?)

data class ServerData(val lists: List<Triple<String, String, List<Item>>>)

object Sources {
    val kinds = linkedMapOf("subsonic" to "Navidrome / Subsonic", "jellyfin" to "Jellyfin / Emby", "webdav" to "WebDAV")

    // 有的服务器挂在 Cloudflare 后面，会拦 okhttp 默认的 UA
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", "Weiguang").build()) }
        .build()

    private var genDir: File? = null

    fun init(dir: File) { genDir = File(dir, "gen").apply { mkdirs() } }

    var servers: List<Server> = emptyList()
        private set

    fun load() {
        servers = try {
            val a = JSONArray(Prefs.servers)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Server(o.getString("id"), o.getString("k"), o.optString("n"), o.getString("u"), o.optString("user"), o.optString("p"), o.optString("t"), o.optString("uid"))
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun persist() {
        Prefs.servers = JSONArray().apply {
            for (s in servers) put(JSONObject().put("id", s.id).put("k", s.kind).put("n", s.name).put("u", s.url)
                .put("user", s.user).put("p", s.pass).put("t", s.token).put("uid", s.uid))
        }.toString()
    }

    fun remove(id: String) { servers = servers.filter { it.id != id }; persist() }

    private fun base(u: String) = u.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    // 连一下试试，通了就存下来；返回 null=成功，否则是原因
    suspend fun add(kind: String, url: String, user: String, pass: String): String? = withContext(Dispatchers.IO) {
        var s = Server(UUID.randomUUID().toString().take(8), kind, "", base(url), user.trim(), pass)
        try {
            s = when (kind) {
                "subsonic" -> { subsonic(s, "ping"); s.copy(name = Uri.parse(s.url).host ?: "Navidrome") }
                "jellyfin" -> jellyLogin(s)
                else -> { propfind(s, s.url); s.copy(name = Uri.parse(s.url).host ?: "WebDAV") }
            }
        } catch (e: SourceError) { return@withContext e.message } catch (e: Throwable) {
            return@withContext "连不上：${e.message ?: e.javaClass.simpleName}"
        }
        servers = servers + s
        persist()
        null
    }

    class SourceError(msg: String) : Exception(msg)

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw SourceError("账号或密码不对")
            if (!r.isSuccessful) throw SourceError("服务器返回 ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    // ---------- Subsonic / Navidrome ----------

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun auth(s: Server): String {
        val salt = UUID.randomUUID().toString().take(10)
        return "u=" + Uri.encode(s.user) + "&t=" + md5(s.pass + salt) + "&s=" + salt + "&v=1.16.1&c=weiguang"
    }

    private fun subsonic(s: Server, method: String, q: String = ""): JSONObject {
        val o = JSONObject(get("${s.url}/rest/$method.view?${auth(s)}&f=json$q")).getJSONObject("subsonic-response")
        if (o.optString("status") != "ok") {
            val e = o.optJSONObject("error")
            throw SourceError(if (e?.optInt("code") == 40) "账号或密码不对" else e?.optString("message") ?: "服务器拒绝了")
        }
        return o
    }

    private fun subItem(s: Server, o: JSONObject) = Item(
        "${s.id}:${o.getString("id")}", o.optString("title"), o.optString("artist"),
        o.optLong("duration") * 1000,
        o.optString("coverArt").takeIf { it.isNotEmpty() }?.let { "${s.url}/rest/getCoverArt.view?id=${Uri.encode(it)}&size=300&${auth(s)}" },
    )

    private fun arr(o: JSONObject?, key: String): List<JSONObject> {
        val v = o?.opt(key) ?: return emptyList()
        return when (v) {
            is JSONArray -> List(v.length()) { v.getJSONObject(it) }
            is JSONObject -> listOf(v)
            else -> emptyList()
        }
    }

    private fun fetchSubsonic(s: Server): ServerData {
        val lists = ArrayList<Triple<String, String, List<Item>>>()
        for (p in arr(subsonic(s, "getPlaylists").optJSONObject("playlists"), "playlist").take(60)) {
            val pl = subsonic(s, "getPlaylist", "&id=" + Uri.encode(p.getString("id"))).optJSONObject("playlist")
            lists += Triple(p.getString("id"), p.optString("name"), arr(pl, "entry").map { subItem(s, it) })
        }
        val all = ArrayList<Item>()
        while (all.size < 5000) {
            val r = subsonic(s, "search3", "&query=&artistCount=0&albumCount=0&songCount=500&songOffset=${all.size}")
            val got = arr(r.optJSONObject("searchResult3"), "song").map { subItem(s, it) }
            all += got
            if (got.size < 500) break
        }
        lists += Triple("all", "${s.name} · 全部", all)
        return ServerData(lists)
    }

    // ---------- Jellyfin / Emby ----------

    private const val CLIENT = "MediaBrowser Client=\"Weiguang\", Device=\"Android\", DeviceId=\"weiguang\", Version=\"1\""

    private fun jellyLogin(s: Server): Server {
        val body = JSONObject().put("Username", s.user).put("Pw", s.pass).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("${s.url}/Users/AuthenticateByName").post(body)
            .header("Authorization", CLIENT).header("X-Emby-Authorization", CLIENT).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw SourceError("账号或密码不对")
            if (!r.isSuccessful) throw SourceError("服务器返回 ${r.code}")
            val o = JSONObject(r.body?.string() ?: "{}")
            val name = o.optJSONObject("SessionInfo")?.optString("ServerName")?.takeIf { it.isNotEmpty() } ?: Uri.parse(s.url).host ?: "Jellyfin"
            return s.copy(token = o.getString("AccessToken"), uid = o.getJSONObject("User").getString("Id"), name = name)
        }
    }

    // 新版 Jellyfin 默认只认 Authorization 里的 Token（X-Emby-Token 是旧写法，留给 Emby）；两个一起带，实测不冲突
    private fun jelly(s: Server, path: String): JSONObject =
        JSONObject(get("${s.url}$path", mapOf("Authorization" to "$CLIENT, Token=\"${s.token}\"", "X-Emby-Token" to s.token)))

    private fun jellyItem(s: Server, o: JSONObject): Item {
        val img = when {
            o.optJSONObject("ImageTags")?.has("Primary") == true -> o.getString("Id")
            o.optString("AlbumId").isNotEmpty() -> o.getString("AlbumId")
            else -> null
        }
        val artists = o.optJSONArray("Artists")?.let { a -> List(a.length()) { a.getString(it) } }?.joinToString("/")
        return Item(
            "${s.id}:${o.getString("Id")}", o.optString("Name"), artists?.takeIf { it.isNotEmpty() } ?: o.optString("AlbumArtist"),
            o.optLong("RunTimeTicks") / 10_000,
            img?.let { "${s.url}/Items/$it/Images/Primary?maxHeight=300&api_key=${s.token}" },
        )
    }

    private fun items(o: JSONObject) = o.optJSONArray("Items")?.let { a -> List(a.length()) { a.getJSONObject(it) } } ?: emptyList()

    private fun fetchJellyfin(s: Server): ServerData {
        val lists = ArrayList<Triple<String, String, List<Item>>>()
        for (p in items(jelly(s, "/Users/${s.uid}/Items?IncludeItemTypes=Playlist&Recursive=true")).take(60)) {
            val id = p.getString("Id")
            lists += Triple(id, p.optString("Name"), items(jelly(s, "/Playlists/$id/Items?UserId=${s.uid}")).map { jellyItem(s, it) })
        }
        val all = ArrayList<Item>()
        while (all.size < 5000) {
            val got = items(jelly(s, "/Users/${s.uid}/Items?IncludeItemTypes=Audio&Recursive=true&SortBy=SortName&StartIndex=${all.size}&Limit=500")).map { jellyItem(s, it) }
            all += got
            if (got.size < 500) break
        }
        lists += Triple("all", "${s.name} · 全部", all)
        return ServerData(lists)
    }

    // ---------- WebDAV ----------

    private val audioExt = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma")

    private class Entry(val href: String, val dir: Boolean)

    private fun propfind(s: Server, url: String): List<Entry> {
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
            .toRequestBody("application/xml".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1")
            .header("Authorization", Credentials.basic(s.user, s.pass, Charsets.UTF_8)).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw SourceError("账号或密码不对")
            if (r.code != 207 && !r.isSuccessful) throw SourceError("服务器返回 ${r.code}，确认地址是 WebDAV 目录")
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(r.body!!.charStream())
            val out = ArrayList<Entry>()
            var href = ""; var dir = false; var inHref = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "response" -> { href = ""; dir = false }
                        "href" -> inHref = true
                        "collection" -> dir = true
                    }
                    XmlPullParser.TEXT -> if (inHref) href += p.text.trim()
                    XmlPullParser.END_TAG -> when (p.name) {
                        "href" -> inHref = false
                        "response" -> if (href.isNotEmpty()) out += Entry(href, dir)
                    }
                }
            }
            return out
        }
    }

    // 文件名「歌手 - 歌名.mp3」就拆开；否则歌名=文件名，歌手=所在文件夹名
    private fun davItem(s: Server, url: String, folder: String): Item {
        val file = URLDecoder.decode(url.substringAfterLast('/'), "UTF-8").substringBeforeLast('.')
        val parts = file.split(" - ", limit = 2)
        val (artist, title) = if (parts.size == 2) parts[0].trim() to parts[1].trim() else folder to file.trim()
        return Item("${s.id}:$url", title.replace(Regex("^\\d{1,3}[ ._-]+"), ""), artist, 0, null)
    }

    private fun fetchWebdav(s: Server): ServerData {
        val root = Uri.parse(s.url)
        val origin = "${root.scheme}://${root.encodedAuthority}"
        val folders = LinkedHashMap<String, MutableList<Item>>()
        val all = ArrayList<Item>()
        val seen = HashSet<String>()
        fun walk(url: String, depth: Int) {
            if (depth > 4 || all.size >= 3000 || !seen.add(url.trimEnd('/'))) return
            val name = URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8")
            for (e in propfind(s, url)) {
                val abs = if (e.href.startsWith("http")) e.href else origin + e.href
                if (abs.trimEnd('/') == url.trimEnd('/')) continue
                if (e.dir) walk(abs, depth + 1)
                else if (abs.substringAfterLast('.').lowercase() in audioExt) {
                    val it = davItem(s, abs, name)
                    all += it
                    folders.getOrPut(name.ifEmpty { s.name }) { ArrayList() } += it
                }
            }
        }
        walk(s.url.trimEnd('/') + "/", 0)
        val lists = folders.entries.filter { it.value.size >= 2 }.take(60).map { Triple(it.key, it.key, it.value.toList()) }
        return ServerData(lists + Triple("all", "${s.name} · 全部", all))
    }

    // ---------- 对外 ----------

    suspend fun fetch(s: Server): ServerData = withContext(Dispatchers.IO) {
        when (s.kind) {
            "subsonic" -> fetchSubsonic(s)
            "jellyfin" -> fetchJellyfin(s)
            else -> fetchWebdav(s)
        }
    }

    // 播放地址：自己的服务器按类型拼，AI 作曲的歌是本机文件
    fun uriOf(loc: String): Uri? {
        if (loc.startsWith("gen:")) return genDir?.let { Uri.fromFile(File(it, loc.removePrefix("gen:"))) }
        val sid = loc.substringBefore(':')
        val iid = loc.substringAfter(':')
        val s = servers.firstOrNull { it.id == sid } ?: return null
        return Uri.parse(when (s.kind) {
            "subsonic" -> "${s.url}/rest/stream.view?id=${Uri.encode(iid)}&${auth(s)}"
            "jellyfin" -> "${s.url}/Audio/$iid/stream?static=true&api_key=${s.token}"
            else -> iid
        })
    }

    // WebDAV 的歌要带账号；播放器按地址前缀找对应的服务器
    fun authFor(uri: Uri): String? {
        val u = uri.toString()
        val s = servers.firstOrNull { it.kind == "webdav" && u.startsWith(it.url) } ?: return null
        return Credentials.basic(s.user, s.pass, Charsets.UTF_8)
    }

    // 点歌时在自己的服务器上找这首（WebDAV 只能看同步下来的列表，在曲库里已经有了）
    suspend fun find(title: String, artist: String): Item? = withContext(Dispatchers.IO) {
        val t = Library.norm(title)
        val a = Library.norm(artist.split("/", "、", "&", ",").first())
        for (s in servers) {
            val found = try {
                when (s.kind) {
                    "subsonic" -> arr(subsonic(s, "search3", "&query=" + Uri.encode(title) + "&artistCount=0&albumCount=0&songCount=20").optJSONObject("searchResult3"), "song").map { subItem(s, it) }
                    "jellyfin" -> items(jelly(s, "/Users/${s.uid}/Items?IncludeItemTypes=Audio&Recursive=true&Limit=20&searchTerm=" + Uri.encode(title))).map { jellyItem(s, it) }
                    else -> emptyList()
                }
            } catch (_: Throwable) { emptyList() }
            found.firstOrNull { Library.norm(it.title) == t && (a.isEmpty() || Library.norm(it.artist).contains(a)) }?.let { return@withContext it }
        }
        null
    }

    fun genFile(name: String): File? = genDir?.let { File(it, name) }
}

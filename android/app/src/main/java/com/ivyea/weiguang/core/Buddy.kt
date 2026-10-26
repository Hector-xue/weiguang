package com.ivyea.weiguang.core

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

// feel：这条是按哪种心情找的歌（可以接着开情绪电台）
data class Msg(val me: Boolean, val text: String, val songs: List<Long> = emptyList(), val feel: String? = null)

// 一种心情在星图上的位置；to 不为空表示「带我走出来」：从这里慢慢走到 to
data class Feel(val name: String, val say: String, val v: Float, val e: Float, val reply: String, val to: Pair<Float, Float>? = null)

val Feels = listOf(
    Feel("低落", "有点低落", 0.2f, 0.25f, "先陪你待一会儿，再慢慢亮起来：", 0.72f to 0.5f),
    Feel("疲惫", "好累", 0.4f, 0.12f, "不吵你，一点点把精神找回来：", 0.62f to 0.42f),
    Feel("烦躁", "心里很烦", 0.3f, 0.8f, "先顺着这股劲，再一点点静下来：", 0.55f to 0.2f),
    Feel("想哭", "想哭", 0.12f, 0.15f, "想哭就哭一会儿，我在："),
    Feel("平静", "挺平静的", 0.55f, 0.2f, "那就安安静静的："),
    Feel("专注", "要专心做事", 0.5f, 0.35f, "不打扰你，这些适合专心："),
    Feel("开心", "心情不错", 0.85f, 0.65f, "好心情，接着亮下去："),
    Feel("想燃", "想燃一下", 0.75f, 0.92f, "音量调大："),
)

// 微光这个"伙伴"：对话点歌、此刻的一句话、解读歌词、给歌打情绪标签、连播和情绪电台。
// 选歌只从用户自己的曲库里挑（网易云按歌名点不了歌，只有曲库里的歌有 ID；别的 App 按歌名点）
object Buddy {
    // 伙伴的性格：名字 + 塞进提示词里的一句描述
    val personas = listOf(
        "温柔" to "温柔伙伴，说话像朋友，简短自然",
        "元气" to "元气伙伴，热情开朗、爱用感叹，但不吵闹",
        "损友" to "嘴有点毒但心很软的老朋友，会调侃用户，但从不伤人",
        "安静" to "话很少的伙伴，只说最必要的一两句，留白多",
        "文艺" to "有点文艺的伙伴，偶尔用一点诗意的比喻，不矫情",
    )

    fun persona(): String = personas.getOrElse(Prefs.persona) { personas[0] }.second

    private val _msgs = MutableStateFlow<List<Msg>>(emptyList())
    val msgs: StateFlow<List<Msg>> = _msgs
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _thought = MutableStateFlow<String?>(null)
    val thought: StateFlow<String?> = _thought
    private var thoughtKey = ""

    private val _tagMsg = MutableStateFlow("")
    val tagMsg: StateFlow<String> = _tagMsg
    private val _tagging = MutableStateFlow(false)
    val tagging: StateFlow<Boolean> = _tagging

    // 星图连播
    private val _queue = MutableStateFlow<List<Long>>(emptyList())
    val queue: StateFlow<List<Long>> = _queue
    private val _qIdx = MutableStateFlow(0)
    val qIdx: StateFlow<Int> = _qIdx

    fun period(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) {
            in 0..4 -> "深夜"
            in 5..7 -> "清晨"
            in 8..10 -> "上午"
            in 11..12 -> "中午"
            in 13..16 -> "下午"
            in 17..18 -> "傍晚"
            in 19..22 -> "晚上"
            else -> "深夜"
        }
    }

    private fun clock(): String {
        val c = Calendar.getInstance()
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun nowLine(): String {
        val w = Weather.line?.let { "外面$it。" } ?: ""
        val n = NowPlaying.now.value ?: return "${w}现在没在放歌。"
        return "$w${Players.nameOf(n.pkg)}正在${if (n.playing) "放" else "暂停在"}《${n.title}》- ${n.artist}。"
    }

    // 候选：最近听过的在前，再补歌单里的
    private fun candidates(limit: Int = 300): List<Song> {
        val s = Library.state.value
        val ids = LinkedHashSet<Long>()
        s.history.forEach { ids += it.id }
        s.playlists.forEach { p -> p.songIds.forEach { ids += it } }
        return ids.mapNotNull { s.songs[it] }.filter { it.title.isNotEmpty() }.take(limit)
    }

    // 最常听的几位歌手
    fun taste(n: Int = 6): List<String> {
        val s = Library.state.value
        val w = HashMap<String, Int>()
        for (h in s.history) s.songs[h.id]?.artist?.split("/", "、", "&")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { w[it] = (w[it] ?: 0) + h.count }
        return w.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    // 这个点（前后一小时）你常听的歌：至少在两天里都听过
    fun habit(n: Int = 3): List<Song> {
        val s = Library.state.value
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val days = HashMap<Long, HashSet<Int>>()
        for (p in s.plays) {
            c.timeInMillis = p.at
            val d = (c.get(Calendar.HOUR_OF_DAY) - hour + 24) % 24
            if (d <= 1 || d == 23) days.getOrPut(p.id) { HashSet() } += c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
        }
        return days.entries.filter { it.value.size >= 2 }.sortedByDescending { it.value.size }
            .mapNotNull { s.songs[it.key] }.filter { it.title.isNotEmpty() }.take(n)
    }

    private fun profile(): String = buildString {
        val t = taste()
        if (t.isNotEmpty()) append("用户最常听的歌手：").append(t.joinToString("、")).append("。")
        val h = habit(5)
        if (h.isNotEmpty()) append("这个时段用户常听：").append(h.joinToString("、") { "《${it.title}》" }).append("。")
    }

    // ---------- 按心情找歌 ----------

    suspend fun feel(f: Feel) {
        if (_busy.value) return
        _msgs.value = _msgs.value + Msg(true, f.say)
        _busy.value = true
        Diary.logMood(f.name)
        try {
            val stars = tagged()
            val msg = when {
                stars.size >= 20 -> Msg(false, f.reply, walk(stars, f))
                Ai.ready -> askAi(
                    "我现在${f.say}。" + (if (f.to != null) "先陪我待在这个情绪里，再一首首慢慢把我带出来。" else "陪着这个心情就好。") + "按播放顺序挑 8 首。",
                    max = 8,
                )
                else -> {
                    val picks = localPick(f.say)
                    Msg(false, if (picks.isEmpty()) "曲库还是空的，先去「曲库」同步一下歌单吧。" else "先凭直觉挑几首。填上 AI 密钥、在星图里给歌打好标签，按心情找歌会准很多。", picks)
                }
            }
            _msgs.value = _msgs.value + msg.copy(feel = f.name)
        } catch (e: Ai.AiError) {
            _msgs.value = _msgs.value + Msg(false, "${e.message}。", localPick(f.say), f.name)
        } finally {
            _busy.value = false
        }
    }

    // 在星图上走一条路：停留型就在这片星域里挑，带出来型从起点一步步走到终点，每一步取最近的没用过的歌
    // 打过标签的歌，能量用 AI 估的和实测的合起来
    private class Star(val id: Long, val v: Float, val e: Float)

    private fun tagged(): List<Star> = Library.state.value.songs.values
        .filter { it.v != null && it.e != null && it.title.isNotEmpty() }
        .map { Star(it.id, it.v!!, Library.energy(it) ?: it.e!!) }

    private fun d2(s: Star, v: Float, e: Float) = (s.v - v) * (s.v - v) + (s.e - e) * (s.e - e)

    private fun walk(tagged: List<Star>, f: Feel, n: Int = 10): List<Long> {
        val playing = NowPlaying.now.value?.songId
        val used = HashSet<Long>().apply { playing?.let { add(it) } }
        val out = ArrayList<Long>()
        fun nearest(v: Float, e: Float): Star? = tagged.filter { it.id !in used }.minByOrNull { d2(it, v, e) }
        if (f.to == null) {
            // 最近的 16 首里随机取 8 首，免得每次一模一样
            val near = tagged.filter { it.id != playing }.sortedBy { d2(it, f.v, f.e) }.take(16).shuffled().take(8)
            return near.sortedBy { d2(it, f.v, f.e) }.map { it.id }
        }
        for (i in 0 until n) {
            // 前三首停在原地，之后再走，像先接住、再带走
            val k = ((i - 2).coerceAtLeast(0) / (n - 3f)).coerceIn(0f, 1f)
            val v = f.v + (f.to.first - f.v) * k
            val e = f.e + (f.to.second - f.e) * k
            val s = nearest(v, e) ?: break
            used += s.id
            out += s.id
        }
        return out
    }

    // ---------- 对话点歌 ----------

    suspend fun ask(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _busy.value) return
        _msgs.value = _msgs.value + Msg(true, t)
        _busy.value = true
        try {
            _msgs.value = _msgs.value + (if (Ai.ready) askAi(t) else askLocal(t))
        } catch (e: Ai.AiError) {
            val picks = localPick(t)
            _msgs.value = _msgs.value + Msg(false, "${e.message}。" + if (picks.isNotEmpty()) "先凭直觉给你挑几首：" else "", picks)
        } finally {
            _busy.value = false
        }
    }

    private suspend fun askAi(text: String, max: Int = 5): Msg {
        val cand = candidates()
        val system = """
            你是「微光」，陪用户听歌的${persona()}。用户用${apps()}听歌。
            需要推荐歌时，只能从「候选歌曲」里挑，用它的 id，绝不编造列表外的歌。
            只回复一个 JSON：{"reply":"一两句中文回应，不超过40字","songs":[按推荐顺序的 id，最多$max 个]}
            结合用户的口味、习惯、此刻的时间和天气来挑，像一个了解用户的老朋友。
            用户只是聊天、不需要歌时，songs 给空数组。候选里实在没有合适的，就在 reply 里坦白说，并建议去搜什么。
        """.trimIndent()
        val user = buildString {
            append("现在是").append(period()).append(' ').append(clock()).append("。").append(nowLine()).append(profile()).append('\n')
            append("候选歌曲（id|歌名|歌手）：\n")
            cand.forEach { append(it.id).append('|').append(it.title).append('|').append(it.artist).append('\n') }
            append("\n用户说：").append(text)
        }
        val history = _msgs.value.dropLast(1).takeLast(6).map { (if (it.me) "user" else "assistant") to it.text }
        val raw = Ai.chat(system, user, history, maxTokens = 400)
        val o = Ai.json(raw) ?: return Msg(false, raw.take(120))
        val known = Library.state.value.songs
        val ids = ArrayList<Long>()
        o.optJSONArray("songs")?.let { a -> for (i in 0 until a.length()) a.optLong(i).takeIf { it in known }?.let { ids += it } }
        return Msg(false, o.optString("reply").ifBlank { "给你挑了这些：" }, ids.distinct().take(max))
    }

    private fun apps(): String {
        val s = Library.state.value
        val srcs = s.history.take(200).mapNotNull { s.songs[it.id] }.map { Players.nameOf(it.src ?: Ncm.PKG) }.distinct()
        return srcs.ifEmpty { listOf("音乐 App") }.joinToString("、")
    }

    private fun askLocal(text: String): Msg {
        val picks = localPick(text)
        val reply = when {
            candidates().isEmpty() -> "曲库还是空的，先去「曲库」同步一下歌单吧。"
            picks.isEmpty() -> "曲库里没找到合适的。"
            else -> "按你说的挑了几首。在设置里填上 AI 密钥，我会更懂你。"
        }
        return Msg(false, reply, picks)
    }

    // 没有 AI 时的直觉：歌名/歌手里有用户说的词就挑它，否则从最近常听的里随机
    private fun localPick(text: String): List<Long> {
        val cand = candidates()
        if (cand.isEmpty()) return emptyList()
        val words = text.split(Regex("[\\s，。！？,.!?、]+")).filter { it.length >= 2 }
        val hit = cand.filter { s -> words.any { w -> s.title.contains(w, true) || s.artist.contains(w, true) } }
        return (if (hit.isNotEmpty()) hit else cand.take(40).shuffled()).take(3).map { it.id }
    }

    fun clearChat() { _msgs.value = emptyList() }

    // ---------- 此刻的一句话 ----------

    private fun localThought(): String = if (Weather.rainy) "外面在下雨，听点安静的吧。" else when (period()) {
        "深夜" -> "夜深了，声音放轻一点。"
        "清晨" -> "早，今天也慢慢来。"
        "上午" -> "上午的光刚好，适合一首轻快的。"
        "中午" -> "歇一会儿，听首歌再继续。"
        "下午" -> "下午有点长，我陪你听一会儿。"
        "傍晚" -> "天色暗下来了，想听点什么？"
        else -> "今天辛苦了，挑一首喜欢的吧。"
    }

    suspend fun think() {
        val n = NowPlaying.now.value
        val key = period() + "|" + (n?.songId ?: 0L) + "|" + Ai.ready + "|" + Prefs.persona + "|" + Weather.line
        if (key == thoughtKey) return
        thoughtKey = key
        if (!Ai.ready) { _thought.value = localThought(); return }
        _thought.value = _thought.value ?: localThought()
        try {
            val recent = Library.state.value.let { s -> s.history.take(5).mapNotNull { s.songs[it.id]?.title } }
            val line = Ai.chat(
                "你是「微光」，陪用户听歌的${persona()}。根据时间、天气和用户在听的歌，说一句自然的心里话，不超过26个字，不要引号，不要提问超过一次。",
                "现在是${period()} ${clock()}。${nowLine()}最近听过：${recent.joinToString("、")}。${profile()}",
                maxTokens = 80, temperature = 1.0,
            )
            _thought.value = line.trim().trim('"', '“', '”', '「', '」').take(40)
        } catch (_: Ai.AiError) {
            _thought.value = localThought()
        }
    }

    // ---------- 解读歌词 ----------

    fun isLyric(line: String?): Boolean {
        if (line.isNullOrBlank()) return false
        return !Regex("^(作词|作曲|编曲|词|曲|制作人|混音|母带|演唱|原唱|监制|吉他|和声|录音|出品)\\s*[:：]").containsMatchIn(line.trim())
    }

    suspend fun explain(line: String): String {
        if (!Ai.ready) return "在「设置」里填上 AI 密钥，微光就能陪你读歌词了。"
        val n = NowPlaying.now.value
        return try {
            Ai.chat(
                "你是「微光」，${persona()}。用两三句话解读这句歌词的意象和情绪，可以结合整首歌，但不确定的背景不要编。不超过100字，口语化。",
                "歌曲《${n?.title ?: ""}》- ${n?.artist ?: ""}，歌词：「$line」",
                maxTokens = 260,
            )
        } catch (e: Ai.AiError) {
            e.message ?: "AI 出错了"
        }
    }

    // ---------- 情绪标签（星图坐标） ----------

    suspend fun tag() {
        if (_tagging.value) return
        if (!Ai.ready) { _tagMsg.value = "先在「设置」里填 AI 密钥"; return }
        _tagging.value = true
        try {
            val todo = Library.state.value.songs.values.filter { it.v == null && it.title.isNotEmpty() }
            var done = 0
            for (batch in todo.chunked(40)) {
                _tagMsg.value = "正在给歌打标签 $done/${todo.size}"
                val user = batch.joinToString("\n") { "${it.id}|${it.title}|${it.artist}" }
                val raw = Ai.chat(
                    """
                    给每首歌估两个 0 到 1 的值：v=情绪明亮度（0 悲伤低落，1 明亮开心），e=能量（0 安静舒缓，1 激烈有力）。
                    按你对这首歌的了解判断；不了解的按歌名和歌手推测。
                    只回复 JSON，键是 id，值是 [v,e]，例如 {"123":[0.3,0.2]}
                    """.trimIndent(),
                    user, maxTokens = 2000, temperature = 0.3,
                )
                val o = Ai.json(raw) ?: continue
                val m = HashMap<Long, Pair<Float, Float>>()
                for (k in o.keys()) {
                    val a = o.optJSONArray(k) ?: continue
                    val id = k.toLongOrNull() ?: continue
                    if (a.length() >= 2) m[id] = a.optDouble(0).toFloat() to a.optDouble(1).toFloat()
                }
                Library.setMoods(m)
                done += batch.size
            }
            _tagMsg.value = ""
        } catch (e: Ai.AiError) {
            _tagMsg.value = e.message ?: "打标签出错"
        } finally {
            _tagging.value = false
        }
    }

    // ---------- 连播：音乐 App 播完一首，微光接着放线上的下一首 ----------

    // 情绪电台：按某种心情一直放下去，快放完时自动续上
    private val _radio = MutableStateFlow<Feel?>(null)
    val radio: StateFlow<Feel?> = _radio

    private fun play(ctx: Context, id: Long) {
        val s = Library.state.value.songs[id] ?: return
        Players.play(ctx, s)
    }

    fun startQueue(ctx: Context, ids: List<Long>) {
        if (ids.isEmpty()) return
        _radio.value = null
        _queue.value = ids
        _qIdx.value = 0
        // 整串都在自建曲库里：一次交给微光自己的播放器，无缝连着放
        val songs = Library.state.value.songs
        val all = ids.mapNotNull { songs[it] }
        if (all.size == ids.size && all.all { it.loc != null } && (Prefs.player.isEmpty() || Prefs.player == Local.PKG) && Local.play(ctx, all)) return
        play(ctx, ids[0])
    }

    fun startRadio(ctx: Context, f: Feel, first: List<Long>) {
        startQueue(ctx, first.ifEmpty { radioBatch(f, emptySet()) })
        if (_queue.value.isNotEmpty()) _radio.value = f
    }

    fun stopQueue() { _queue.value = emptyList(); _qIdx.value = 0; _radio.value = null }

    // 电台续歌：打过标签的歌够多就在这种心情的落点附近挑（带出来型落在终点），
    // 否则从常听的里随机；最近放过的不重复
    private fun radioBatch(f: Feel, avoid: Set<Long>): List<Long> {
        val recent = Library.state.value.plays.takeLast(60).map { it.id }.toSet() + avoid
        val stars = tagged()
        if (stars.size >= 20) {
            val (v, e) = f.to ?: (f.v to f.e)
            val pool = stars.filter { it.id !in recent }.ifEmpty { stars.filter { it.id !in avoid } }
            return pool.sortedBy { d2(it, v, e) }.take(14).shuffled().take(6).map { it.id }
        }
        val cand = candidates().map { it.id }
        return (cand.filter { it !in recent }.ifEmpty { cand }).take(80).shuffled().take(6)
    }

    // 这首是不是线上那首：ID 一样，或者歌名歌手对得上（别的 App 按歌名点的，可能是另一个版本）
    private fun same(n: Now, id: Long): Boolean {
        if (n.songId == id) return true
        val s = Library.state.value.songs[id] ?: return false
        return Library.keyOf(n.title, n.artist) == Library.keyOf(s.title, s.artist) ||
            (Library.norm(n.title).isNotEmpty() && Library.norm(n.title) == Library.norm(s.title))
    }

    suspend fun runQueue(ctx: Context) {
        var nearEnd = false
        while (true) {
            delay(1000)
            val q = _queue.value
            val n = NowPlaying.now.value
            if (q.isEmpty() || n == null) { nearEnd = false; continue }
            // 手动切到了线上的另一首，进度跟过去
            val at = q.indexOfFirst { same(n, it) }
            if (at >= 0 && at != _qIdx.value) _qIdx.value = at
            val cur = q.getOrNull(_qIdx.value)
            if (cur == null) { stopQueue(); continue }
            if (same(n, cur)) {
                nearEnd = n.durationMs > 0 && n.positionNow() > n.durationMs - 4000
            } else if (nearEnd) {
                // 刚播到尾巴、音乐 App 切去了它自己的下一首 → 换成线上的下一首
                nearEnd = false
                val next = _qIdx.value + 1
                val f = _radio.value
                if (f != null && q.size - next <= 2) _queue.value = q + radioBatch(f, q.toSet())
                val q2 = _queue.value
                when {
                    next >= q2.size -> stopQueue()
                    // 播放器自己已经接到线上的下一首（微光自己的播放器整串放的时候）
                    same(n, q2[next]) -> _qIdx.value = next
                    else -> { _qIdx.value = next; play(ctx, q2[next]) }
                }
            }
        }
    }
}

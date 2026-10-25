package com.ivyea.weiguang.core

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

data class Msg(val me: Boolean, val text: String, val songs: List<Long> = emptyList())

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

// 微光这个"伙伴"：对话点歌、此刻的一句话、解读歌词、给歌打情绪标签、星图连播。
// 选歌只从用户自己的曲库里挑（网易云按歌名点不了歌，只有曲库里的歌有 ID）
object Buddy {
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
        val n = NowPlaying.now.value ?: return "网易云现在没在放歌。"
        return "网易云正在${if (n.playing) "放" else "暂停在"}《${n.title}》- ${n.artist}。"
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
        try {
            val tagged = Library.state.value.songs.values.filter { it.v != null && it.e != null && it.title.isNotEmpty() }
            val msg = when {
                tagged.size >= 20 -> Msg(false, f.reply, walk(tagged, f))
                Ai.ready -> askAi(
                    "我现在${f.say}。" + (if (f.to != null) "先陪我待在这个情绪里，再一首首慢慢把我带出来。" else "陪着这个心情就好。") + "按播放顺序挑 8 首。",
                    max = 8,
                )
                else -> {
                    val picks = localPick(f.say)
                    Msg(false, if (picks.isEmpty()) "曲库还是空的，先去「曲库」同步一下歌单吧。" else "先凭直觉挑几首。填上 AI 密钥、在星图里给歌打好标签，按心情找歌会准很多。", picks)
                }
            }
            _msgs.value = _msgs.value + msg
        } catch (e: Ai.AiError) {
            _msgs.value = _msgs.value + Msg(false, "${e.message}。", localPick(f.say))
        } finally {
            _busy.value = false
        }
    }

    // 在星图上走一条路：停留型就在这片星域里挑，带出来型从起点一步步走到终点，每一步取最近的没用过的歌
    private fun walk(tagged: List<Song>, f: Feel, n: Int = 10): List<Long> {
        val playing = NowPlaying.now.value?.songId
        val used = HashSet<Long>().apply { playing?.let { add(it) } }
        val out = ArrayList<Long>()
        fun nearest(v: Float, e: Float): Song? = tagged.filter { it.id !in used }
            .minByOrNull { (it.v!! - v) * (it.v - v) + (it.e!! - e) * (it.e - e) }
        if (f.to == null) {
            // 最近的 16 首里随机取 8 首，免得每次一模一样
            val near = tagged.filter { it.id != playing }
                .sortedBy { (it.v!! - f.v) * (it.v - f.v) + (it.e!! - f.e) * (it.e - f.e) }.take(16).shuffled().take(8)
            return near.sortedBy { (it.v!! - f.v) * (it.v - f.v) + (it.e!! - f.e) * (it.e - f.e) }.map { it.id }
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
            你是「微光」，一个陪用户听歌的温柔伙伴，说话像朋友，简短自然。用户用网易云听歌。
            需要推荐歌时，只能从「候选歌曲」里挑，用它的 id，绝不编造列表外的歌。
            只回复一个 JSON：{"reply":"一两句中文回应，不超过40字","songs":[按推荐顺序的 id，最多$max 个]}
            结合用户的口味和习惯来挑，像一个了解他的老朋友。
            用户只是聊天、不需要歌时，songs 给空数组。候选里实在没有合适的，就在 reply 里坦白说，并建议去网易云搜什么。
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

    private fun localThought(): String = when (period()) {
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
        val key = period() + "|" + (n?.songId ?: 0L) + "|" + Ai.ready
        if (key == thoughtKey) return
        thoughtKey = key
        if (!Ai.ready) { _thought.value = localThought(); return }
        _thought.value = _thought.value ?: localThought()
        try {
            val recent = Library.state.value.let { s -> s.history.take(5).mapNotNull { s.songs[it.id]?.title } }
            val line = Ai.chat(
                "你是「微光」，陪用户听歌的温柔伙伴。根据时间和用户在听的歌，说一句自然的心里话，不超过26个字，不要引号，不要提问超过一次。",
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
                "你是「微光」。用两三句话解读这句歌词的意象和情绪，可以结合整首歌，但不确定的背景不要编。不超过100字，口语化。",
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

    // ---------- 星图连播：网易云播完一首，微光接着放线上的下一首 ----------

    fun startQueue(ctx: Context, ids: List<Long>) {
        if (ids.isEmpty()) return
        _queue.value = ids
        _qIdx.value = 0
        Ncm.playSong(ctx, ids[0])
    }

    fun stopQueue() { _queue.value = emptyList(); _qIdx.value = 0 }

    suspend fun runQueue(ctx: Context) {
        var nearEnd = false
        while (true) {
            delay(1000)
            val q = _queue.value
            val n = NowPlaying.now.value
            if (q.isEmpty() || n == null) { nearEnd = false; continue }
            val cur = q.getOrNull(_qIdx.value)
            if (cur == null) { stopQueue(); continue }
            if (n.songId == cur) {
                nearEnd = n.durationMs > 0 && n.positionNow() > n.durationMs - 4000
            } else if (nearEnd) {
                // 刚播到尾巴、网易云切去了它自己的下一首 → 换成线上的下一首
                nearEnd = false
                val next = _qIdx.value + 1
                if (next < q.size) { _qIdx.value = next; Ncm.playSong(ctx, q[next]) } else stopQueue()
            }
        }
    }
}

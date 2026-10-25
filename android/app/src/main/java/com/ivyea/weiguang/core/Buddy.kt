package com.ivyea.weiguang.core

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

data class Msg(val me: Boolean, val text: String, val songs: List<Long> = emptyList())

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

    private suspend fun askAi(text: String): Msg {
        val cand = candidates()
        val system = """
            你是「微光」，一个陪用户听歌的温柔伙伴，说话像朋友，简短自然。用户用网易云听歌。
            需要推荐歌时，只能从「候选歌曲」里挑，用它的 id，绝不编造列表外的歌。
            只回复一个 JSON：{"reply":"一两句中文回应，不超过40字","songs":[按推荐顺序的 id，最多5个]}
            用户只是聊天、不需要歌时，songs 给空数组。候选里实在没有合适的，就在 reply 里坦白说，并建议去网易云搜什么。
        """.trimIndent()
        val user = buildString {
            append("现在是").append(period()).append(' ').append(clock()).append("。").append(nowLine()).append('\n')
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
        return Msg(false, o.optString("reply").ifBlank { "给你挑了这些：" }, ids.distinct().take(5))
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
                "现在是${period()} ${clock()}。${nowLine()}最近听过：${recent.joinToString("、")}",
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

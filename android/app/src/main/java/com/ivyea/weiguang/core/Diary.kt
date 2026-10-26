package com.ivyea.weiguang.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

data class MoodLog(val at: Long, val name: String)
data class DiaryState(val moods: List<MoodLog> = emptyList(), val notes: Map<String, String> = emptyMap())

// 一天：听了哪些歌（来自播放流水）、选过什么心情、微光写的一句话
data class Day(val key: String, val start: Long, val plays: List<Play>, val moods: List<MoodLog>) {
    // 按次数排的歌
    fun top(): List<Pair<Long, Int>> = plays.groupingBy { it.id }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
}

// 听歌日记：每天听了什么、什么心情。心情记录和日记文字存在 diary.json，歌从曲库的播放流水里取
object Diary {
    private val _state = MutableStateFlow(DiaryState())
    val state: StateFlow<DiaryState> = _state
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "diary.json")
        load()
    }

    fun keyOf(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun startOf(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun logMood(name: String) {
        val s = _state.value
        _state.value = s.copy(moods = (s.moods + MoodLog(System.currentTimeMillis(), name)).takeLast(2000))
        save()
    }

    // 最近有记录的日子，新的在前
    fun days(plays: List<Play>, moods: List<MoodLog>, limit: Int = 60): List<Day> {
        val byDay = LinkedHashMap<String, Pair<MutableList<Play>, MutableList<MoodLog>>>()
        val starts = HashMap<String, Long>()
        for (p in plays.asReversed()) {
            val k = keyOf(p.at)
            starts.getOrPut(k) { startOf(p.at) }
            byDay.getOrPut(k) { mutableListOf<Play>() to mutableListOf() }.first += p
        }
        for (m in moods) {
            val k = keyOf(m.at)
            starts.getOrPut(k) { startOf(m.at) }
            byDay.getOrPut(k) { mutableListOf<Play>() to mutableListOf() }.second += m
        }
        return byDay.entries.map { (k, v) -> Day(k, starts[k]!!, v.first.sortedBy { it.at }, v.second) }
            .sortedByDescending { it.start }.take(limit)
    }

    // 让微光给这一天写两句。今天的随时可以重写，过去的写一次就留着
    suspend fun write(day: Day): String {
        val songs = Library.state.value.songs
        val list = day.top().take(15).mapNotNull { (id, n) -> songs[id]?.let { "《${it.title}》${it.artist}" + if (n > 1) "×$n" else "" } }
        val tagged = day.plays.mapNotNull { songs[it.id] }.filter { it.v != null && it.e != null }
        val avg = if (tagged.isEmpty()) "" else "这天听的歌平均明亮度 %.2f、能量 %.2f（0~1）。".format(tagged.map { it.v!! }.average(), tagged.map { it.e!! }.average())
        val c = Calendar.getInstance()
        val hours = day.plays.map { c.timeInMillis = it.at; c.get(Calendar.HOUR_OF_DAY) }
        val span = if (hours.isEmpty()) "" else "从 ${hours.min()} 点听到 ${hours.max()} 点。"
        val moods = day.moods.joinToString("、") { it.name }
        val text = Ai.chat(
            "你是「微光」，陪用户听歌的${Buddy.persona()}。根据用户这一天听的歌，替用户写一则很短的听歌日记：两句话，不超过60字，第二人称「你」，具体、温柔，不要空话套话，不要编造歌里没有的事。",
            "日期 ${day.key}。$span${if (moods.isNotEmpty()) "这天选过的心情：$moods。" else ""}$avg\n听过：${list.joinToString("、")}",
            maxTokens = 160, temperature = 0.9,
        ).trim().trim('"', '“', '”')
        val s = _state.value
        _state.value = s.copy(notes = s.notes + (day.key to text))
        save()
        return text
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            val moods = ArrayList<MoodLog>()
            o.optJSONArray("moods")?.let { a -> for (i in 0 until a.length()) { val m = a.getJSONArray(i); moods += MoodLog(m.getLong(0), m.getString(1)) } }
            val notes = HashMap<String, String>()
            o.optJSONObject("notes")?.let { n -> for (k in n.keys()) notes[k] = n.getString(k) }
            _state.value = DiaryState(moods, notes)
        } catch (_: Throwable) {}
    }

    private fun save() {
        val f = file ?: return
        val s = _state.value
        val text = JSONObject()
            .put("moods", JSONArray().apply { for (m in s.moods) put(JSONArray().put(m.at).put(m.name)) })
            .put("notes", JSONObject().apply { for ((k, v) in s.notes) put(k, v) })
            .toString()
        io.execute {
            try { val tmp = File(f.path + ".tmp"); tmp.writeText(text); tmp.renameTo(f) } catch (_: Throwable) {}
        }
    }
}

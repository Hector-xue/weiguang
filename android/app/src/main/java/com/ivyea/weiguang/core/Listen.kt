package com.ivyea.weiguang.core

// 开着微光听歌时，从频谱里实测每首歌有多「激烈」：频谱逐帧变化量（鼓点、起伏）为主，高频占比为辅。
// 系统给的频谱随音量走，所以只比相对变化；攒够 15 秒才算数，结果在曲库里按排名折算成能量
object Listen {
    private var song: Long? = null
    private var frames = 0
    private var flux = 0.0
    private var high = 0.0

    fun frame(fluxNow: Float, highNow: Float) {
        val id = NowPlaying.now.value?.takeIf { it.playing }?.songId
        if (id != song) { flush(); song = id }
        if (id == null) return
        frames++
        flux += fluxNow
        high += highNow
        // 一首歌最多收 2 分钟，够了就结算
        if (frames >= 2400) { flush(); song = id }
    }

    // 声音停了（暂停、关了界面）就结算这一段
    fun pause() = flush()

    private fun flush() {
        val id = song
        val n = frames
        frames = 0
        if (id == null || n < 300) { flux = 0.0; high = 0.0; return }
        val m = (flux / n * 0.7 + high / n * 0.3).toFloat()
        flux = 0.0; high = 0.0
        Library.setMeasured(id, m)
    }
}

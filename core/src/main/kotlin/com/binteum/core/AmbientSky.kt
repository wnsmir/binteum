package com.binteum.core

enum class BackgroundMode(val title: String) {
    AUTO("시간에 맞춰 자동"), DAWN("아침 노을"), DAY("한낮의 하늘"), SUNSET("저녁 노을"), NIGHT("밤"), BLACK("단색 블랙"), WHITE("단색 화이트");
    val isFlat: Boolean get() = this == BLACK || this == WHITE
}

data class SkyColors(val top: Int, val middle: Int, val bottom: Int)

/** Local clock schedule, not location-based sunrise/sunset or weather data. */
object AmbientSky {
    private val dawn = SkyColors(0xFF819BC9.toInt(), 0xFFDA9EB2.toInt(), 0xFFF3B18A.toInt())
    private val day = SkyColors(0xFF548DD4.toInt(), 0xFF80B9E6.toInt(), 0xFFC7DFED.toInt())
    private val sunset = SkyColors(0xFF6C73B0.toInt(), 0xFFC27EAA.toInt(), 0xFFF2A077.toInt())
    private val night = SkyColors(0xFF1D2B46.toInt(), 0xFF343051.toInt(), 0xFF493552.toInt())
    private val anchors = listOf(0 to night, 300 to night, 420 to dawn, 660 to day,
        900 to day, 1080 to sunset, 1260 to night, 1440 to night)

    fun colors(mode: BackgroundMode, minuteOfDay: Int): SkyColors {
        require(minuteOfDay in 0..1439)
        return when (mode) {
            BackgroundMode.BLACK -> SkyColors(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt())
            BackgroundMode.WHITE -> SkyColors(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
            BackgroundMode.DAWN -> dawn
            BackgroundMode.DAY -> day
            BackgroundMode.SUNSET -> sunset
            BackgroundMode.NIGHT -> night
            BackgroundMode.AUTO -> {
                val index = anchors.indexOfLast { it.first <= minuteOfDay }
                val (start, a) = anchors[index]
                val (end, b) = anchors[index + 1]
                val raw = (minuteOfDay - start).toFloat() / (end - start)
                val t = raw * raw * (3 - 2 * raw)
                SkyColors(blend(a.top, b.top, t), blend(a.middle, b.middle, t), blend(a.bottom, b.bottom, t))
            }
        }
    }

    fun blend(a: Int, b: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val x = (a ushr shift) and 255
            val y = (b ushr shift) and 255
            return (x + (y - x) * t).toInt().coerceIn(0, 255) shl shift
        }
        return 0xFF000000.toInt() or channel(16) or channel(8) or channel(0)
    }
}

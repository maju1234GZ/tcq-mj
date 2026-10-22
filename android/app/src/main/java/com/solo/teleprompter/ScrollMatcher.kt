package com.solo.teleprompter

/**
 * 语音跟读滚动匹配器 —— 严格按《插件接口契约》第五节实现。
 *
 * 1. 归一化：去除标点、空白、转小写（中文按字）。
 * 2. 维护 currentIndex（归一化台本中已滚到的字符索引，初始 0）。
 * 3. 每次收到识别全文，取归一化后尾部最多 30 字作为锚点 anchor。
 * 4. 在台本归一化文本 [currentIndex, currentIndex+120] 窗口内，
 *    找 anchor 的最长公共后缀匹配位置（允许 1~2 字编辑距离）。
 * 5. 匹配成功且新位置 > currentIndex：更新 currentIndex（只前进）。
 * 6. 匹配失败：保持原位（不回滚）。
 */
class ScrollMatcher(script: String) {

    /** 归一化后的台本 */
    val normScript: String

    /** normToOrig[i] = 归一化第 i 个字符在原始台本中的下标 */
    private val normToOrig: IntArray

    /** 已滚到的字符索引（归一化空间） */
    var currentIndex: Int = 0
        private set

    init {
        val sb = StringBuilder(script.length)
        val map = ArrayList<Int>(script.length)
        script.forEachIndexed { i, c ->
            if (isKeep(c)) {
                sb.append(Character.toLowerCase(c))
                map.add(i)
            }
        }
        normScript = sb.toString()
        normToOrig = IntArray(map.size) { map[it] }
    }

    companion object {
        private const val ANCHOR_LEN = 30
        private const val WINDOW_LEN = 120
        private const val MAX_EDITS = 2
        private const val MIN_MATCH = 4

        /** 归一化：仅保留字母和数字（中文按字保留），转小写 */
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                if (isKeep(c)) sb.append(Character.toLowerCase(c))
            }
            return sb.toString()
        }

        private fun isKeep(c: Char): Boolean = Character.isLetterOrDigit(c)
    }

    /** currentIndex 对应的原始台本字符索引（已读到的位置，用于滚动/高亮） */
    fun originalPosition(): Int {
        if (currentIndex <= 0 || normToOrig.isEmpty()) return 0
        val i = (currentIndex - 1).coerceAtMost(normToOrig.size - 1)
        return normToOrig[i] + 1
    }

    /** 手动 seek（用户在悬浮窗内拖动文本时调用，允许回退） */
    fun seekToOriginal(origIndex: Int) {
        var idx = 0
        while (idx < normToOrig.size && normToOrig[idx] < origIndex) idx++
        currentIndex = idx
    }

    /**
     * 收到识别累计全文，返回更新后的 currentIndex。
     * 匹配失败或无法前进时保持原值。
     */
    fun update(recognizedText: String): Int {
        val anchor = normalize(recognizedText).takeLast(ANCHOR_LEN)
        if (anchor.length < MIN_MATCH) return currentIndex
        val n = normScript.length
        if (currentIndex >= n) return currentIndex
        val end = (currentIndex + WINDOW_LEN).coerceAtMost(n)
        val window = normScript.substring(currentIndex, end)
        val matchEnd = fuzzySuffixMatchEnd(anchor, window)
        if (matchEnd != null && matchEnd > 0) {
            val newIndex = currentIndex + matchEnd
            if (newIndex > currentIndex) currentIndex = newIndex
        }
        return currentIndex
    }

    /**
     * 在 window 中寻找 anchor 的最长公共后缀匹配（编辑距离 <= MAX_EDITS）。
     * 返回匹配区间在 window 中的结束偏移（exclusive）；失败返回 null。
     * 多个相同最长匹配取最靠前（最小 j），保证行为保守。
     */
    private fun fuzzySuffixMatchEnd(anchor: String, window: String): Int? {
        val m = anchor.length
        val w = window.length
        if (w == 0 || m == 0) return null

        // d[i][j] = anchor 后缀（从第 i 字到末尾）与 "window 中结束于 j（exclusive）的某个子串" 的最小编辑距离
        val d = Array(m + 1) { IntArray(w + 1) }
        for (j in 0..w) d[m][j] = 0 // 空后缀可在任意位置零代价匹配
        for (i in 0 until m) d[i][0] = m - i // window 空前缀：只能删除 anchor 剩余字符
        for (i in m - 1 downTo 0) {
            for (j in 1..w) {
                val cost = if (anchor[i] == window[j - 1]) 0 else 1
                d[i][j] = minOf(
                    d[i + 1][j] + 1,          // 跳过 anchor 字符（删除）
                    d[i][j - 1] + 1,          // 跳过 window 字符（插入）
                    d[i + 1][j - 1] + cost    // 对齐一个字符（匹配/替换）
                )
            }
        }

        var bestJ = -1
        var bestK = 0
        for (j in 1..w) {
            // i 越小后缀越长；扫描第一个满足编辑距离阈值的 i 即得该 j 处最长匹配
            var i = 0
            while (i <= m && d[i][j] > MAX_EDITS) i++
            val k = m - i
            if (k >= MIN_MATCH && k > bestK) {
                bestK = k
                bestJ = j
            }
        }
        return if (bestJ > 0) bestJ else null
    }
}

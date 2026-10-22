/**
 * 语音跟读滚动匹配器
 * 实现契约第五节算法：
 * 1. 归一化：去除标点、空白、转小写（中文按字）
 * 2. 维护 currentIndex（已滚到的字符索引，初始 0）
 * 3. 每次收到识别全文，取其归一化尾部最多 30 字作为锚点 anchor
 * 4. 在台本归一化文本 [currentIndex, currentIndex+120] 窗口内，
 *    找 anchor 的最长公共后缀匹配位置（允许 1~2 字编辑距离的模糊匹配，
 *    使用滑窗海明相似度 + 错位对齐近似，不引入外部库）
 * 5. 匹配成功且新位置 > currentIndex：更新 currentIndex（只前进）
 * 6. 匹配失败：保持原位（不回滚）
 */

const ANCHOR_LEN = 30
const WINDOW_LEN = 120
const MIN_MATCH = 3

function normalizeChar(ch) {
  // 空白、标点、符号（含 emoji）剔除，其余保留（已在外层转小写）
  return !/[\s\p{P}\p{S}]/u.test(ch)
}

export function normalize(text) {
  const src = (text || '').toLowerCase()
  let out = ''
  for (let i = 0; i < src.length; i++) {
    if (normalizeChar(src[i])) out += src[i]
  }
  return out
}

export class ScrollMatcher {
  /**
   * @param {string} fullText 完整台本文本（纯文本，\n 分行）
   */
  constructor(fullText) {
    this.fullText = fullText || ''
    this.currentIndex = 0 // 归一化文本索引：已读到的位置（下一个待读字符）

    // 构建归一化文本 + 每个归一化字符在原文中的索引映射
    const src = this.fullText.toLowerCase()
    let norm = ''
    this.rawIndex = []
    for (let i = 0; i < src.length; i++) {
      if (normalizeChar(src[i])) {
        norm += src[i]
        this.rawIndex.push(i)
      }
    }
    this.norm = norm
  }

  /**
   * 推入识别累计全文，返回台本原文中的新滚动位置（字符索引）。
   * 匹配失败或无需前进时返回当前位置（不回滚）。
   * @param {string} recognizedFullText 自 startSpeech 以来的累计识别全文
   * @returns {number} 原文字符索引
   */
  push(recognizedFullText) {
    const norm = normalize(recognizedFullText)
    if (!norm || !this.norm) return this.toRaw(this.currentIndex)

    const anchor = norm.slice(-ANCHOR_LEN)
    const win = this.norm.slice(this.currentIndex, this.currentIndex + WINDOW_LEN)
    const offset = this._matchAnchor(anchor, win)

    if (offset > 0) {
      const next = this.currentIndex + offset
      if (next > this.currentIndex) this.currentIndex = next
    }
    return this.toRaw(this.currentIndex)
  }

  /** 归一化索引 -> 原文索引 */
  toRaw(normIdx) {
    if (normIdx <= 0) return 0
    if (normIdx >= this.rawIndex.length) return this.fullText.length
    return this.rawIndex[normIdx]
  }

  /**
   * 在窗口 win 内为 anchor 找最长公共后缀匹配。
   * 返回匹配终点在 win 内的偏移（即新的 currentIndex 增量），失败返回 -1。
   */
  _matchAnchor(anchor, win) {
    const n = anchor.length
    const m = win.length
    const maxL = Math.min(n, m)
    const minL = Math.min(MIN_MATCH, n)

    for (let L = maxL; L >= minL; L--) {
      const suffix = anchor.slice(n - L)
      const tol = L >= 10 ? 2 : (L >= 5 ? 1 : 0)
      let bestEnd = -1
      for (let s = 0; s + L <= m; s++) {
        if (this._fuzzyEqual(suffix, win, s, L, tol)) {
          bestEnd = s + L // 取窗口内最靠后的匹配终点
        }
      }
      if (bestEnd >= 0) return bestEnd
    }
    return -1
  }

  /**
   * 滑窗模糊比较：suffix 与 win[s..s+L) 的海明距离 <= tol 视为匹配。
   * 额外尝试 ±1 错位对齐，近似容忍 1 字插入/删除。
   */
  _fuzzyEqual(suffix, win, s, L, tol) {
    if (this._hamming(suffix, win, s, L, tol) <= tol) return true
    if (tol > 0) {
      if (s > 0 && this._hamming(suffix, win, s - 1, L, tol) <= tol) return true
      if (s + L < win.length && this._hamming(suffix, win, s + 1, L, tol) <= tol) return true
    }
    return false
  }

  _hamming(suffix, win, s, L, tol) {
    let diff = 0
    for (let k = 0; k < L; k++) {
      if (suffix[k] !== win[s + k]) {
        diff++
        if (diff > tol) return diff
      }
    }
    return diff
  }
}

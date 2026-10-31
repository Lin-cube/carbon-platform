/**
 * 极简 Markdown 渲染器（零依赖）。
 *
 * <p>为什么需要：大模型（DeepSeek）的回答天然带 Markdown 语法
 * （`**加粗**`、`| 表格 |`、`- 列表`），而聊天区若用 `{{ text }}`
 * 纯文本渲染，用户看到的就是一堆星号和竖线，观感很差。
 *
 * <p>为什么不用 marked / markdown-it：本项目遵循「不引入不必要依赖」，
 * 而 AI 输出只需要一个很小的子集；自己实现 ~80 行即可，且能完全控制
 * 转义顺序，避免 XSS。
 *
 * <p><b>安全</b>：先对 HTML 特殊字符做转义，再套用 Markdown 规则，
 * 因此模型或搜索结果里即使夹带 `<script>` 也只会被当成文本显示。
 */

/** 转义 HTML，防止注入 */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 行内语法：行内代码、加粗 */
function inline(text: string): string {
  return escapeHtml(text)
    .replace(/`([^`]+)`/g, '<code class="md-code">$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
}

/** 判断是否表格分隔行，如 |---|---| */
function isTableSeparator(line: string): boolean {
  return /^\s*\|[\s:|-]+\|\s*$/.test(line) && line.includes('-')
}

/** 把一行拆成单元格 */
function splitCells(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
}

/**
 * 渲染 Markdown 子集：标题、无序/有序列表、表格、行内代码、加粗、段落。
 *
 * @param src 原始 Markdown 文本
 * @returns 可安全用于 v-html 的 HTML 字符串
 */
export function renderMarkdown(src: string): string {
  if (!src) return ''
  const lines = src.replace(/\r\n/g, '\n').split('\n')
  const out: string[] = []
  let i = 0
  let listOpen = false
  let para: string[] = []

  const closeList = () => {
    if (listOpen) {
      out.push('</ul>')
      listOpen = false
    }
  }
  const flushPara = () => {
    if (para.length) {
      out.push(`<p class="md-p">${para.map(inline).join('<br/>')}</p>`)
      para = []
    }
  }
  const closeAll = () => {
    closeList()
    flushPara()
  }

  while (i < lines.length) {
    const line = lines[i]

    // ── 表格：当前行是表头，下一行是分隔行 ──
    if (line.trim().startsWith('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      closeAll()
      const header = splitCells(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        rows.push(splitCells(lines[i]))
        i++
      }
      out.push(
        '<table class="md-table"><thead><tr>' +
          header.map((h) => `<th>${inline(h)}</th>`).join('') +
          '</tr></thead><tbody>' +
          rows
            .map((r) => '<tr>' + r.map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>')
            .join('') +
          '</tbody></table>'
      )
      continue
    }

    // ── 标题（### 等） ──
    const heading = line.match(/^(#{1,4})\s+(.*)$/)
    if (heading) {
      closeAll()
      out.push(`<div class="md-h">${inline(heading[2])}</div>`)
      i++
      continue
    }

    // ── 无序列表 ──
    const bullet = line.match(/^\s*[-*]\s+(.*)$/)
    if (bullet) {
      flushPara()
      if (!listOpen) {
        out.push('<ul class="md-ul">')
        listOpen = true
      }
      out.push(`<li>${inline(bullet[1])}</li>`)
      i++
      continue
    }

    // ── 有序列表（渲染成同样样式的列表，保留序号） ──
    const ordered = line.match(/^\s*(\d+)\.\s+(.*)$/)
    if (ordered) {
      flushPara()
      if (!listOpen) {
        out.push('<ul class="md-ul">')
        listOpen = true
      }
      out.push(`<li>${ordered[1]}. ${inline(ordered[2])}</li>`)
      i++
      continue
    }

    // ── 空行：结束当前块 ──
    if (!line.trim()) {
      closeAll()
      i++
      continue
    }

    // ── 普通文本：累积成段 ──
    closeList()
    para.push(line)
    i++
  }

  closeAll()
  return out.join('')
}

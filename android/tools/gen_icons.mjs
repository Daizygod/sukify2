// Иконки приложения — те же векторы, что и в вебе (frontend/src/components/Icon.vue,
// пути сняты с оригинального Spotify). Генерирует Kotlin-объект SukifyIcons.
//
// Запуск из корня репозитория:
//   docker run --rm -v "$PWD:/repo" -w /repo node:22-alpine node android/tools/gen_icons.mjs

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { dirname } from 'node:path'

const SRC = 'frontend/src/components/Icon.vue'
const OUT = 'android/app/src/main/kotlin/ru/nepalimsya/sukify/ui/icons/SukifyIcons.kt'

const vue = readFileSync(SRC, 'utf8')
const start = vue.indexOf('const icons = {')
if (start < 0) throw new Error('Не нашёл объект icons в Icon.vue')

// Объект — обычный литерал JS: вырезаем его по скобкам и вычисляем.
let i = vue.indexOf('{', start)
let depth = 0
let end = -1
let quote = null
for (; i < vue.length; i++) {
  const c = vue[i]
  if (quote) {
    if (c === '\\') i++
    else if (c === quote) quote = null
    continue
  }
  if (c === "'" || c === '"' || c === '`') quote = c
  else if (c === '/' && vue[i + 1] === '/') i = vue.indexOf('\n', i)
  else if (c === '{') depth++
  else if (c === '}' && --depth === 0) {
    end = i
    break
  }
}
const literal = vue.slice(vue.indexOf('{', start), end + 1)
const icons = new Function(`return (${literal})`)()

const kotlinName = (name) => name.charAt(0).toUpperCase() + name.slice(1)
const esc = (s) => s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$')

const entries = Object.entries(icons).sort(([a], [b]) => a.localeCompare(b))
const body = entries
  .map(([name, { vb, d }]) => `    val ${kotlinName(name)}: ImageVector by lazy { svgIcon("${name}", "${vb}", "${esc(d)}") }`)
  .join('\n')

// Иконки не из веба (Material Symbols) дописаны в конец файла руками —
// переносим этот блок как есть, иначе перегенерация молча их стирает.
const MARK = '    // Ниже — не из веба'
let extra = ''
if (existsSync(OUT)) {
  const prev = readFileSync(OUT, 'utf8')
  const at = prev.indexOf(MARK)
  if (at >= 0) extra = '\n' + prev.slice(at, prev.lastIndexOf('}')).replace(/\s+$/, '') + '\n'
}

const kt = `// Сгенерировано android/tools/gen_icons.mjs из frontend/src/components/Icon.vue — руками не править.
package ru.nepalimsya.sukify.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Иконки веба (${entries.length} шт.) как ImageVector: те же глифы, что в браузере. */
object SukifyIcons {
${body}
${extra}}
`

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, kt)
console.log(`${entries.length} иконок → ${OUT}`)

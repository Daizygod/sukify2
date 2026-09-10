<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import api from '@/lib/api'
import CoverImage from '@/components/CoverImage.vue'
import { formatNumber, plural } from '@/lib/format'

const data = ref(null)
const loading = ref(true)
const year = ref(null)

// Прослушивания лежат в UTC, а «во сколько я слушаю» имеет смысл только в
// местном времени — пояс берём из браузера и считаем на стороне Postgres.
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone

async function load() {
  loading.value = true
  try {
    const { data: res } = await api.get('/me/spotify-stats', {
      params: { tz: timezone, ...(year.value ? { year: year.value } : {}) },
    })
    data.value = res
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(year, load)

const totals = computed(() => data.value?.totals)
const hours = (ms) => Math.round((ms || 0) / 3600000)
const minutes = (ms) => Math.round((ms || 0) / 60000)
/** «371 ч» либо «14 мин» — часы врут о мелочах, минуты о больших числах. */
const listened = (ms) => (hours(ms) >= 1 ? `${formatNumber(hours(ms))} ч` : `${minutes(ms)} мин`)

const period = computed(() => {
  if (year.value) return String(year.value)
  const years = data.value?.years || []
  if (!years.length) return ''
  const from = years[0].year
  const to = years[years.length - 1].year
  return from === to ? String(from) : `${from}–${to}`
})

/** Доля дослушанных до конца — Spotify считает скипом всё короче 30 секунд. */
const finishedPct = computed(() => {
  const t = totals.value
  return t?.plays ? Math.round((t.full_plays / t.plays) * 100) : 0
})
const shufflePct = computed(() => {
  const t = totals.value
  return t?.plays ? Math.round((t.shuffled / t.plays) * 100) : 0
})

const WEEKDAYS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']
const MONTHS = ['Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн', 'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек']
const PLATFORMS = {
  windows: 'Windows',
  android: 'Android',
  ios: 'iPhone / iPad',
  mac: 'Mac',
  playstation: 'PlayStation',
  xbox: 'Xbox',
  web: 'Браузер',
  cast: 'Chromecast',
  linux: 'Linux',
  partner: 'Партнёрские устройства',
  other: 'Другое',
}

/** Столбики строим от общего максимума — иначе высота ничего не значит. */
function scale(rows, key = 'ms') {
  const max = Math.max(1, ...rows.map((r) => r[key] || 0))
  return (v) => Math.max(v > 0 ? 2 : 0, Math.round(((v || 0) / max) * 100))
}
function peakIndex(rows, key = 'ms') {
  let best = 0
  rows.forEach((r, i) => {
    if ((r[key] || 0) > (rows[best][key] || 0)) best = i
  })
  return best
}

const byHour = computed(() => data.value?.by_hour || [])
const byWeekday = computed(() => data.value?.by_weekday || [])
const byMonth = computed(() => data.value?.by_month || [])
const byYear = computed(() => (data.value?.years || []).map((y) => ({ ...y, bucket: y.year })))
const platforms = computed(() => data.value?.platforms || [])

const hourPeak = computed(() => (byHour.value.length ? peakIndex(byHour.value) : -1))
const weekdayPeak = computed(() => (byWeekday.value.length ? peakIndex(byWeekday.value) : -1))
</script>

<template>
  <div class="content-pad sst">
    <nav class="sst__tabs">
      <RouterLink to="/stats" class="sst__tab">В Sukify</RouterLink>
      <span class="sst__tab sst__tab--on">Из Spotify</span>
    </nav>

    <h1 class="sst__title">Твой Spotify</h1>

    <p v-if="loading && !data" class="muted">Считаю…</p>

    <template v-else-if="data && !data.has_data">
      <p class="muted sst__empty">
        История прослушиваний ещё не перенесена.
        Загрузи архив Spotify на странице <RouterLink to="/import">импорта</RouterLink> —
        и здесь появятся все твои годы: топы, часы и по каким устройствам ты слушал.
      </p>
    </template>

    <template v-else-if="data">
      <!-- Фильтр по годам: один ряд над всеми графиками -->
      <div class="sst__chips">
        <button type="button" class="sst__chip" :class="{ on: year === null }" @click="year = null">
          Все годы
        </button>
        <button
          v-for="y in data.years"
          :key="y.year"
          type="button"
          class="sst__chip"
          :class="{ on: year === y.year }"
          @click="year = y.year"
        >
          {{ y.year }}
        </button>
      </div>

      <section class="sst__hero">
        <div class="sst__herofig">{{ formatNumber(hours(totals.ms)) }}</div>
        <div class="sst__herolabel">
          {{ plural(hours(totals.ms), 'час', 'часа', 'часов') }} музыки за {{ period }}
        </div>
      </section>

      <div class="sst__tiles">
        <div class="sst__tile">
          <div class="sst__tilelabel">Прослушиваний</div>
          <div class="sst__tileval">{{ formatNumber(totals.plays) }}</div>
        </div>
        <div class="sst__tile">
          <div class="sst__tilelabel">Разных треков</div>
          <div class="sst__tileval">{{ formatNumber(totals.tracks) }}</div>
        </div>
        <div class="sst__tile">
          <div class="sst__tilelabel">Разных артистов</div>
          <div class="sst__tileval">{{ formatNumber(totals.artists) }}</div>
        </div>
        <div class="sst__tile">
          <div class="sst__tilelabel">Дослушано до конца</div>
          <div class="sst__tileval">{{ finishedPct }}%</div>
        </div>
        <div class="sst__tile">
          <div class="sst__tilelabel">Включено вперемешку</div>
          <div class="sst__tileval">{{ shufflePct }}%</div>
        </div>
      </div>

      <div class="sst__cols">
        <!-- Топ артистов -->
        <section>
          <h2 class="section-title">Топ артистов</h2>
          <ol class="sst__rank">
            <li v-for="(a, i) in data.top_artists" :key="a.name + i" class="sst__row">
              <span class="sst__num">{{ i + 1 }}</span>
              <CoverImage
                :cover="a.image_url ? { 300: a.image_url } : null"
                :size="40"
                rounded
                class="sst__art"
              />
              <div class="sst__meta">
                <component
                  :is="a.slug ? 'RouterLink' : 'span'"
                  :to="a.slug ? { name: 'artist', params: { slug: a.slug } } : undefined"
                  class="sst__name"
                >{{ a.name }}</component>
                <div class="sst__mini">
                  <div
                    class="sst__minifill"
                    :style="{ width: scale(data.top_artists)(a.ms) + '%' }"
                  />
                </div>
              </div>
              <span class="sst__value">{{ listened(a.ms) }}</span>
            </li>
          </ol>
        </section>

        <!-- Топ треков -->
        <section>
          <h2 class="section-title">Топ треков</h2>
          <ol class="sst__rank">
            <li v-for="(t, i) in data.top_tracks" :key="t.title + t.artist + i" class="sst__row">
              <span class="sst__num">{{ i + 1 }}</span>
              <CoverImage :cover="t.cover" :size="40" class="sst__art" />
              <div class="sst__meta">
                <div class="sst__name">{{ t.title }}</div>
                <div class="muted sst__sub">{{ t.artist }}</div>
              </div>
              <span class="sst__value">
                {{ formatNumber(t.plays) }} {{ plural(t.plays, 'раз', 'раза', 'раз') }}
              </span>
            </li>
          </ol>
        </section>
      </div>

      <!-- Часы суток -->
      <section class="sst__chart">
        <h2 class="section-title">Когда ты слушаешь</h2>
        <p class="muted sst__note">
          Часы по местному времени ({{ timezone }}).
          <template v-if="hourPeak >= 0">
            Пик — в {{ String(hourPeak).padStart(2, '0') }}:00.
          </template>
        </p>
        <div class="sst__bars sst__bars--hours">
          <div
            v-for="h in byHour"
            :key="h.bucket"
            class="sst__barslot"
            :title="`${String(h.bucket).padStart(2, '0')}:00 — ${formatNumber(h.plays)} ${plural(h.plays, 'прослушивание', 'прослушивания', 'прослушиваний')}, ${formatNumber(hours(h.ms))} ч`"
          >
            <div class="sst__bar" :style="{ height: scale(byHour)(h.ms) + '%' }" />
          </div>
        </div>
        <!-- Подписей 24 — по одной на столбик, текст только у каждого
             четвёртого: иначе метки не сойдутся со своими часами. -->
        <div class="sst__axis">
          <span v-for="h in byHour" :key="h.bucket">
            {{ h.bucket % 4 === 0 ? String(h.bucket).padStart(2, '0') : '' }}
          </span>
        </div>
      </section>

      <div class="sst__cols">
        <!-- Дни недели -->
        <section class="sst__chart">
          <h2 class="section-title">По дням недели</h2>
          <p class="muted sst__note">
            <template v-if="weekdayPeak >= 0">Больше всего — в {{ WEEKDAYS[weekdayPeak] }}.</template>
          </p>
          <div class="sst__bars">
            <div
              v-for="(d, i) in byWeekday"
              :key="d.bucket"
              class="sst__barslot"
              :title="`${WEEKDAYS[i]} — ${formatNumber(hours(d.ms))} ч, ${formatNumber(d.plays)} ${plural(d.plays, 'прослушивание', 'прослушивания', 'прослушиваний')}`"
            >
              <div class="sst__bar" :style="{ height: scale(byWeekday)(d.ms) + '%' }" />
            </div>
          </div>
          <div class="sst__axis">
            <span v-for="w in WEEKDAYS" :key="w">{{ w }}</span>
          </div>
        </section>

        <!-- По месяцам или по годам -->
        <section class="sst__chart">
          <h2 class="section-title">{{ year ? 'По месяцам' : 'По годам' }}</h2>
          <p class="muted sst__note">Высота столбика — часы прослушивания.</p>
          <div class="sst__bars">
            <div
              v-for="(m, i) in (year ? byMonth : byYear)"
              :key="m.bucket"
              class="sst__barslot"
              :title="`${year ? MONTHS[i] : m.bucket} — ${formatNumber(hours(m.ms))} ч, ${formatNumber(m.plays)} ${plural(m.plays, 'прослушивание', 'прослушивания', 'прослушиваний')}`"
            >
              <div
                class="sst__bar"
                :style="{ height: scale(year ? byMonth : byYear)(m.ms) + '%' }"
              />
            </div>
          </div>
          <div class="sst__axis">
            <span v-for="(m, i) in (year ? byMonth : byYear)" :key="m.bucket">
              {{ year ? MONTHS[i] : m.bucket }}
            </span>
          </div>
        </section>
      </div>

      <!-- Устройства -->
      <section v-if="platforms.length" class="sst__chart">
        <h2 class="section-title">С чего слушал</h2>
        <ul class="sst__plats">
          <li v-for="p in platforms" :key="p.platform" class="sst__plat">
            <span class="sst__platname">{{ PLATFORMS[p.platform] || p.platform }}</span>
            <span class="sst__platbar">
              <span class="sst__platfill" :style="{ width: scale(platforms)(p.ms) + '%' }" />
            </span>
            <span class="sst__value">{{ listened(p.ms) }}</span>
          </li>
        </ul>
      </section>

      <p class="muted sst__foot">
        Считается по истории из архива Spotify: {{ formatNumber(data.totals.plays) }}
        {{ plural(data.totals.plays, 'прослушивание', 'прослушивания', 'прослушиваний') }}
        за {{ period }}. Прослушивания в самом Sukify — на вкладке
        <RouterLink to="/stats">«В Sukify»</RouterLink>.
      </p>
    </template>
  </div>
</template>

<style scoped>
.sst__tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 18px;
}
.sst__tab {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-subdued);
  background: rgba(255, 255, 255, 0.07);
  border-radius: 999px;
  padding: 6px 14px;
}
.sst__tab--on {
  background: #fff;
  color: #000;
}
.sst__title {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.02em;
}
.sst__empty {
  margin-top: 14px;
  max-width: 620px;
  line-height: 1.6;
}
.sst__empty a,
.sst__foot a {
  color: var(--accent);
  text-decoration: underline;
}
.sst__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 18px 0 26px;
}
.sst__chip {
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
  border: 0;
  border-radius: 999px;
  padding: 7px 15px;
  cursor: pointer;
}
.sst__chip:hover {
  background: rgba(255, 255, 255, 0.14);
}
.sst__chip.on {
  background: #fff;
  color: #000;
}

/* Одна геройская цифра на страницу — часы. */
.sst__hero {
  margin-bottom: 26px;
}
.sst__herofig {
  font-size: 64px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.03em;
  color: var(--accent);
}
.sst__herolabel {
  margin-top: 6px;
  font-size: 15px;
  color: var(--text-subdued);
}
.sst__tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 34px;
}
.sst__tile {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 10px;
  padding: 14px 16px;
}
.sst__tilelabel {
  font-size: 12px;
  color: var(--text-subdued);
}
.sst__tileval {
  margin-top: 4px;
  font-size: 24px;
  font-weight: 800;
}
.sst__cols {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 32px;
  margin-bottom: 34px;
}
.sst__rank {
  list-style: none;
  margin: 0;
  padding: 0;
}
.sst__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}
.sst__num {
  width: 22px;
  text-align: right;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--text-subdued);
  flex: none;
}
.sst__art {
  width: 40px;
  flex: 0 0 40px;
}
.sst__meta {
  min-width: 0;
  flex: 1;
}
.sst__name {
  display: block;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
a.sst__name:hover {
  text-decoration: underline;
}
.sst__sub {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sst__mini {
  margin-top: 5px;
  height: 4px;
  border-radius: 0 999px 999px 0;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}
.sst__minifill {
  height: 100%;
  background: var(--accent);
  border-radius: 0 999px 999px 0;
}
.sst__value {
  flex: none;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--text-subdued);
}

/* Столбики: тонкие, скруглены только сверху, стоят на общей базовой линии. */
.sst__chart {
  margin-bottom: 34px;
}
.sst__note {
  font-size: 13px;
  margin: 2px 0 14px;
}
.sst__bars {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 150px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
}
/* Полосы и подписи делят ширину одинаковыми долями — только так метка
   стоит ровно под своим столбиком. Сам столбик уже полосы: остаток — воздух. */
.sst__barslot {
  flex: 1 1 0;
  height: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.sst__bar {
  width: 100%;
  max-width: 24px;
  background: var(--accent);
  border-radius: 4px 4px 0 0;
  transition: height 0.25s ease;
}
.sst__barslot:hover .sst__bar {
  background: #fff;
}
.sst__axis {
  display: flex;
  gap: 2px;
  margin-top: 8px;
  font-size: 11px;
  color: var(--text-subdued);
}
.sst__axis span {
  flex: 1 1 0;
  text-align: center;
  white-space: nowrap;
}
.sst__plats {
  list-style: none;
  margin: 0;
  padding: 0;
  max-width: 560px;
}
.sst__plat {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 5px 0;
}
.sst__platname {
  width: 170px;
  flex: none;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sst__platbar {
  flex: 1;
  height: 10px;
  border-radius: 0 4px 4px 0;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}
.sst__platfill {
  display: block;
  height: 100%;
  background: var(--accent);
  border-radius: 0 4px 4px 0;
}
.sst__foot {
  font-size: 13px;
  line-height: 1.6;
  max-width: 640px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  padding-top: 16px;
}
</style>

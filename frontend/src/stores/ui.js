import { defineStore } from 'pinia'

const LEFT_MIN = 280
const LEFT_MAX = 420
// Правая панель Spotify — 420px максимум.
const RIGHT_MIN = 280
const RIGHT_MAX = 420

function load(key, fallback, max = Infinity) {
  const v = Number(localStorage.getItem(key))
  return Number.isFinite(v) && v > 0 ? Math.min(v, max) : fallback
}

export const useUiStore = defineStore('ui', {
  state: () => ({
    leftWidth: load('ui.leftWidth', 420, LEFT_MAX),
    rightWidth: load('ui.rightWidth', 420, RIGHT_MAX),
    rightOpen: localStorage.getItem('ui.rightOpen') !== '0',
    // What the right panel shows: 'nowplaying' | 'queue' | 'connect' | 'friends' | 'add-tracks'
    rightView: localStorage.getItem('ui.rightView') || 'nowplaying',
    // Плейлист, в который добавляем треки из правой панели ('add-tracks').
    addTracksPlaylist: null,
    // Счётчик правок плейлиста: открытая страница по нему перечитывает состав.
    playlistRevision: 0,
    // Модалка «Новый плейлист» (название + описание) — открывается отовсюду.
    createPlaylistOpen: false,
    fullscreenOpen: false,
    lyricsOpen: false,
    // Мобильный экран «Сейчас играет» (тап по мини-плееру).
    mobileNowOpen: false,
    // Трек-источник для «Создать переход…» из контекстного меню.
    transitionFrom: null,
    // Редактор перехода в правой панели: пара треков { from, to }.
    mixPair: null,
    // Правки в редакторе не сохранены — уход спрашивает подтверждение.
    mixDirty: false,
    listCompact: localStorage.getItem('ui.listCompact') === '1',
  }),
  actions: {
    setLeftWidth(px) {
      this.leftWidth = Math.min(Math.max(px, LEFT_MIN), LEFT_MAX)
      localStorage.setItem('ui.leftWidth', this.leftWidth)
    },
    setRightWidth(px) {
      this.rightWidth = Math.min(Math.max(px, RIGHT_MIN), RIGHT_MAX)
      localStorage.setItem('ui.rightWidth', this.rightWidth)
    },
    /** Player icons toggle their own view: same view closes, other view switches. */
    openRight(view) {
      if (this.rightOpen && this.rightView === view) {
        this.rightOpen = false
      } else {
        this.rightOpen = true
        this.rightView = view
      }
      this.persist()
    },
    closeRight() {
      this.rightOpen = false
      this.addTracksPlaylist = null
      this.persist()
    },

    /**
     * Редактор перехода в правой панели. Открывается чипом между строками;
     * повторный клик по тому же чипу закрывает, как и в оригинале.
     */
    openMixEditor(from, to) {
      const same = this.mixPair?.from?.id === from.id && this.mixPair?.to?.id === to.id
      if (this.rightOpen && this.rightView === 'mix' && same) {
        this.closeMixEditor()

        return
      }
      this.mixPair = { from, to }
      this.mixDirty = false
      this.rightOpen = true
      this.rightView = 'mix'
      localStorage.setItem('ui.rightOpen', '1')
    },
    closeMixEditor() {
      this.mixPair = null
      this.mixDirty = false
      // Возвращаем ту панель, что была до редактора, а не пустоту.
      this.rightView = localStorage.getItem('ui.rightView') || 'nowplaying'
    },

    /** Панель «Добавить в этот плейлист» (кнопка «Добавить» на его странице). */
    toggleAddTracks(playlistId) {
      if (this.rightOpen && this.rightView === 'add-tracks' && this.addTracksPlaylist === playlistId) {
        this.rightOpen = false
        this.addTracksPlaylist = null
      } else {
        this.rightOpen = true
        this.rightView = 'add-tracks'
        this.addTracksPlaylist = playlistId
      }
      // Саму панель не запоминаем: она живёт только рядом со своим плейлистом.
      localStorage.setItem('ui.rightOpen', this.rightOpen ? '1' : '0')
    },
    toggleRight() {
      this.openRight('nowplaying')
    },
    /**
     * Полноэкранный режим и окно текста — взаимоисключающие: показывают одно
     * и то же место экрана. Открывая полный экран, закрываем текст, чтобы не
     * пришлось закрывать его вторым действием после выхода.
     */
    toggleFullscreen() {
      this.fullscreenOpen = !this.fullscreenOpen
      if (this.fullscreenOpen) this.lyricsOpen = false
    },

    toggleLyrics() {
      this.lyricsOpen = !this.lyricsOpen
      if (this.lyricsOpen) this.fullscreenOpen = false
    },

    toggleListCompact() {
      this.listCompact = !this.listCompact
      localStorage.setItem('ui.listCompact', this.listCompact ? '1' : '0')
    },
    persist() {
      localStorage.setItem('ui.rightOpen', this.rightOpen ? '1' : '0')
      localStorage.setItem('ui.rightView', this.rightView)
    },
  },
})

<script setup>
import { computed } from 'vue'
import draggable from 'vuedraggable'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useMenuStore } from '@/stores/menu'

const player = usePlayerStore()
const ui = useUiStore()
const menu = useMenuStore()

const manualModel = computed({
  get: () => player.manualQueue,
  set: (arr) => player.setManualQueue(arr),
})
const upcomingModel = computed({
  get: () => player.upcoming,
  set: (arr) => player.setUpcoming(arr),
})

function openMenu(e, track, opts = {}) {
  menu.openMenu(e, track, opts)
}
</script>

<template>
  <aside class="qp">
    <div class="qp__head">
      <span class="qp__title">Очередь</span>
      <button class="qp__close" title="Закрыть" @click="ui.closeRight()">
        <Icon name="plus" :size="16" style="transform: rotate(45deg)" />
      </button>
    </div>

    <div v-if="!player.currentTrack" class="qp__empty">
      <p class="muted">Включи что-нибудь — здесь появится очередь.</p>
    </div>

    <div v-else class="qp__body">
      <section class="qp__section">
        <h3 class="qp__label">Сейчас играет</h3>
        <div class="qrow qrow--current" @dblclick="player.togglePlay()">
          <CoverImage :cover="player.currentTrack.cover" :size="48" class="qrow__cover" />
          <div class="qrow__meta">
            <div class="qrow__title qrow__title--green">{{ player.currentTrack.title }}</div>
            <div class="qrow__artists">{{ (player.currentTrack.artists || []).map(a => a.name).join(', ') }}</div>
          </div>
          <button class="qrow__more" title="Открыть контекстное меню" @click="openMenu($event, player.currentTrack)">
            <Icon name="more" :size="16" />
          </button>
        </div>
      </section>

      <section v-if="player.manualQueue.length" class="qp__section">
        <div class="qp__labelrow">
          <h3 class="qp__label">Далее в очереди</h3>
          <button class="qp__clear" @click="player.clearManualQueue()">Очистить</button>
        </div>
        <draggable v-model="manualModel" item-key="__qid" ghost-class="qrow--ghost">
          <template #item="{ element }">
            <div
              class="qrow qrow--grab"
              @dblclick="player.playManualItem(element.__qid)"
              @contextmenu.prevent="openMenu($event, element, { queueQid: element.__qid })"
            >
              <div class="qrow__coverwrap">
                <CoverImage :cover="element.cover" :size="48" class="qrow__cover" />
                <button class="qrow__play" @click="player.playManualItem(element.__qid)">
                  <Icon name="play" :size="14" />
                </button>
              </div>
              <div class="qrow__meta">
                <div class="qrow__title">{{ element.title }}</div>
                <div class="qrow__artists">{{ (element.artists || []).map(a => a.name).join(', ') }}</div>
              </div>
              <button class="qrow__more" title="Открыть контекстное меню" @click="openMenu($event, element, { queueQid: element.__qid })">
                <Icon name="more" :size="16" />
              </button>
            </div>
          </template>
        </draggable>
      </section>

      <section v-if="player.upcoming.length" class="qp__section">
        <h3 class="qp__label">
          Далее из:
          <span class="qp__ctx">{{ player.contextName || 'текущего списка' }}</span>
        </h3>
        <draggable v-model="upcomingModel" item-key="__idx" ghost-class="qrow--ghost">
          <template #item="{ element, index }">
            <div
              class="qrow qrow--grab"
              @dblclick="player.playUpcomingItem(index)"
              @contextmenu.prevent="openMenu($event, element, { upcomingIndex: index })"
            >
              <div class="qrow__coverwrap">
                <CoverImage :cover="element.cover" :size="48" class="qrow__cover" />
                <button class="qrow__play" @click="player.playUpcomingItem(index)">
                  <Icon name="play" :size="14" />
                </button>
              </div>
              <div class="qrow__meta">
                <div class="qrow__title">{{ element.title }}</div>
                <div class="qrow__artists">{{ (element.artists || []).map(a => a.name).join(', ') }}</div>
              </div>
              <button class="qrow__more" title="Открыть контекстное меню" @click="openMenu($event, element, { upcomingIndex: index })">
                <Icon name="more" :size="16" />
              </button>
            </div>
          </template>
        </draggable>
      </section>
    </div>
  </aside>
</template>

<style scoped>
.qp {
  height: 100%;
  background: var(--bg-elevated);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.qp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 8px;
}
.qp__title {
  font-weight: 700;
  font-size: 15px;
}
.qp__close {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
}
.qp__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.qp__body {
  overflow-y: auto;
  flex: 1;
  min-height: 0;
  padding: 0 8px 16px;
}
.qp__section {
  margin-top: 16px;
}
.qp__label {
  font-size: 15px;
  font-weight: 700;
  padding: 0 8px 8px;
}
.qp__labelrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 8px;
}
.qp__clear {
  color: var(--text-subdued);
  font-size: 13px;
  font-weight: 700;
}
.qp__clear:hover {
  color: #fff;
  text-decoration: underline;
}
.qp__ctx {
  color: var(--text-subdued);
  font-weight: 700;
}
.qp__empty {
  padding: 24px 16px;
}
.qrow {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  border-radius: 4px;
}
.qrow:hover {
  background: rgba(255, 255, 255, 0.08);
}
.qrow--grab {
  cursor: grab;
}
.qrow--ghost {
  opacity: 0.4;
}
.qrow__coverwrap {
  position: relative;
  flex: 0 0 48px;
}
.qrow__cover {
  width: 48px;
  border-radius: 4px;
}
.qrow__play {
  position: absolute;
  inset: 0;
  display: none;
  place-items: center;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  border-radius: 4px;
}
.qrow:hover .qrow__play {
  display: grid;
}
.qrow__meta {
  min-width: 0;
  flex: 1;
}
.qrow__title {
  color: #fff;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.qrow__title--green {
  color: var(--accent);
}
.qrow__artists {
  color: var(--text-subdued);
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.qrow__more {
  color: var(--text-subdued);
  opacity: 0;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
}
.qrow:hover .qrow__more {
  opacity: 1;
}
.qrow__more:hover {
  color: #fff;
}
</style>

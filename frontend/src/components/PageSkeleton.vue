<script setup>
/**
 * Заглушка страницы, пока грузятся данные: серый «hero» с обложкой и
 * строками, по которым бежит мягкий перелив. Лучше пустоты — видно, что
 * страница уже здесь и вот-вот наполнится.
 */
defineProps({
  round: { type: Boolean, default: false },
  rows: { type: Number, default: 6 },
})
</script>

<template>
  <div class="skel" aria-hidden="true">
    <div class="skel__hero">
      <div class="skel__cover sh" :class="{ 'skel__cover--round': round }"></div>
      <div class="skel__text">
        <div class="skel__line sh" style="width: 72px; height: 12px"></div>
        <div class="skel__line sh" style="width: 62%; height: 48px; margin: 14px 0"></div>
        <div class="skel__line sh" style="width: 38%; height: 14px"></div>
      </div>
    </div>
    <div class="skel__body">
      <div class="skel__actions">
        <div class="skel__play sh"></div>
        <div class="skel__dot sh"></div>
        <div class="skel__dot sh"></div>
      </div>
      <div v-for="i in rows" :key="i" class="skel__row" :style="{ animationDelay: `${i * 60}ms` }">
        <div class="skel__thumb sh"></div>
        <div class="skel__rowtext">
          <div class="skel__line sh" :style="{ width: `${34 + ((i * 37) % 30)}%`, height: '14px' }"></div>
          <div class="skel__line sh" :style="{ width: `${18 + ((i * 23) % 16)}%`, height: '12px', marginTop: '8px' }"></div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.skel__hero {
  display: flex;
  align-items: flex-end;
  gap: 24px;
  padding: 84px 24px 24px;
  background: linear-gradient(180deg, #2a2a2a 0%, #1c1c1c 100%);
  background-image: var(--grain), linear-gradient(180deg, #2a2a2a 0%, #1c1c1c 100%);
}
.skel__cover {
  width: 232px;
  height: 232px;
  flex: 0 0 232px;
  border-radius: 4px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.45);
}
.skel__cover--round {
  border-radius: 50%;
}
.skel__text {
  flex: 1;
  min-width: 0;
  padding-bottom: 8px;
}
.skel__body {
  padding: 24px;
  background: linear-gradient(180deg, #1a1a1a 0, #121212 232px);
}
.skel__actions {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 28px;
}
.skel__play {
  width: 56px;
  height: 56px;
  border-radius: 50%;
}
.skel__dot {
  width: 32px;
  height: 32px;
  border-radius: 50%;
}
.skel__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  animation: skel-in 0.4s ease backwards;
}
.skel__thumb {
  width: 40px;
  height: 40px;
  border-radius: 4px;
  flex: 0 0 40px;
}
.skel__rowtext {
  flex: 1;
}
.skel__line {
  border-radius: 999px;
}
/* Перелив: светлая полоса едет слева направо по всем заглушкам разом. */
.sh {
  background: linear-gradient(90deg, #262626 0%, #333 18%, #262626 36%) 0 0 / 220% 100%;
  animation: skel-shimmer 1.5s linear infinite;
}
@keyframes skel-shimmer {
  from {
    background-position: 120% 0;
  }
  to {
    background-position: -120% 0;
  }
}
@keyframes skel-in {
  from {
    opacity: 0;
  }
}
@media (max-width: 768px) {
  .skel__hero {
    flex-direction: column;
    align-items: center;
    padding: 24px 16px 12px;
  }
  .skel__cover {
    width: min(62vw, 260px);
    height: auto;
    aspect-ratio: 1;
    flex: none;
  }
  .skel__text {
    width: 100%;
  }
  .skel__body {
    padding: 16px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .sh {
    animation: none;
  }
}
</style>

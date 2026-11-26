"""
Проверка сверки с превью на синтетике: бит + «голос» (шум в полосе речи с
ритмом слогов). Запуск в контейнере анализатора:

    docker compose cp docker/analyzer/test_compare.py analyzer:/app/
    docker compose exec analyzer python /app/test_compare.py
"""

import numpy as np

from app import CMP_SR, compare_arrays

SR = CMP_SR


def band_noise(rng, n, lo, hi):
    x = rng.standard_normal(n)
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    spec[(freqs < lo) | (freqs > hi)] = 0
    y = np.fft.irfft(spec, n)
    return (y / (np.abs(y).max() + 1e-9)).astype(np.float32)


def song(seconds, seed, beat_period):
    rng = np.random.default_rng(seed)
    n = int(seconds * SR)
    t = np.arange(n) / SR

    beat = np.zeros(n, np.float32)
    kick_len = int(0.25 * SR)
    kick = (np.sin(2 * np.pi * 60 * np.arange(kick_len) / SR) * np.exp(-np.arange(kick_len) / (0.06 * SR))).astype(np.float32)
    snare_src = band_noise(rng, n, 1000, 4000)
    snare_len = int(0.12 * SR)
    snare_env = np.exp(-np.arange(snare_len) / (0.03 * SR)).astype(np.float32)
    for s in np.arange(0, seconds, beat_period):
        i = int(s * SR)
        j = min(n, i + kick_len)
        beat[i:j] += kick[: j - i]
        i2 = int((s + beat_period / 2) * SR)
        j2 = min(n, i2 + snare_len)
        if i2 < n:
            beat[i2:j2] += 0.15 * snare_src[i2:j2] * snare_env[: j2 - i2]

    # Подложка (пэд, хэты) звучит всё время — в треке не бывает тишины.
    beat += 0.03 * band_noise(rng, n, 500, 5000)

    # Читка: слоги неровные (~5 в секунду, разной длины и силы), фразы по
    # 2–6 секунд с короткими паузами — как в жизни, а не по метроному.
    voice_src = band_noise(rng, n, 800, 4000)
    envelope = np.zeros(n, np.float32)
    pos = 0.0
    while pos < seconds:
        phrase_end = pos + rng.uniform(2, 6)
        while pos < min(phrase_end, seconds):
            length = rng.uniform(0.08, 0.25)
            i, j = int(pos * SR), min(n, int((pos + length) * SR))
            if j > i:
                envelope[i:j] = np.maximum(envelope[i:j], rng.uniform(0.5, 1.0) * np.hanning(j - i))
            pos += length + rng.uniform(0.0, 0.12)
        pos += rng.uniform(0.2, 0.8)
    voice = (0.6 * voice_src * envelope).astype(np.float32)
    del t

    return 0.8 * beat, voice


def cut(full, beat, spans):
    out = full.copy()
    for a, b in spans:
        out[int(a * SR) : int(b * SR)] = beat[int(a * SR) : int(b * SR)]
    return out


def near(found, expected, tolerance_ms=150):
    """
    Каждый вырез найден, и ничего лишнего. Края точно не совпадут: вырез,
    начатый в паузе между словами, виден только с первого слога.
    """
    def overlaps(f, e):
        return f[0] <= e[1] * 1000 + tolerance_ms and f[1] >= e[0] * 1000 - tolerance_ms

    return all(any(overlaps(f, e) for f in found) for e in expected) and all(
        any(overlaps(f, e) for e in expected) for f in found
    )


def main():
    beat, voice = song(120, 1, 0.5)
    full = beat + voice
    spans = [(40.0, 40.6), (52.3, 52.8)]
    censored = cut(full, beat, spans)
    preview = full[30 * SR : 60 * SR]
    preview_cut = censored[30 * SR : 60 * SR]

    # 1. В треке вырезано, превью целое → missing там, где вырезали.
    r = compare_arrays(censored, preview)
    assert r["aligned"], r
    assert abs(r["offset_ms"] - 30000) <= 25, r
    assert near(r["missing"], spans), r
    assert not r["extra"], r
    print("ok: вырезанное в треке найдено", r["missing"])

    # 2. Трек целый, вырезано в превью → extra, а не missing.
    r = compare_arrays(full, preview_cut)
    assert r["aligned"], r
    assert near(r["extra"], spans), r
    assert not r["missing"], r
    print("ok: трек полнее превью", r["extra"])

    # 3. Одинаковые записи, но другой мастеринг и сжатие (громкость, шум).
    # Кодек: шум пропорционален сигналу, плюс общий тихий фон.
    rng = np.random.default_rng(3)
    noisy = (preview * 1.4 * (1 + 0.05 * rng.standard_normal(preview.size)) + 0.0005 * rng.standard_normal(preview.size)).astype(np.float32)
    r = compare_arrays(full * 0.7, noisy)
    assert r["aligned"], r
    assert not r["missing"] and not r["extra"], r
    print("ok: та же запись без ложных тревог, score", r["score"])

    # 4. Другая песня (другой темп) — не выравнивается, выводов не делаем.
    beat2, voice2 = song(120, 99, 0.43)
    r = compare_arrays(beat2 + voice2, preview)
    assert not r["aligned"], r
    assert not r["missing"], r
    print("ok: чужая песня не совпала, score", r["score"])

    # 5. Превью из начала трека (края не сравниваем — затухание).
    r = compare_arrays(censored, full[: 30 * SR])
    assert r["aligned"] and abs(r["offset_ms"]) <= 25, r
    assert not r["missing"], r
    print("ok: превью с начала трека")

    # 6. Разные «песни»: вырезы внутри превью и там, где есть голос.
    for seed in range(10, 18):
        beat, voice = song(150, seed, 0.45 + (seed % 4) * 0.05)
        full = beat + voice
        rng = np.random.default_rng(seed)
        spans = []
        for lo, hi, length in ((33.0, 44.0, 0.5), (46.0, 57.0, 0.35)):
            while True:
                a = float(rng.uniform(lo, hi))
                seg = voice[int(a * SR) : int((a + length) * SR)]
                if np.sqrt((seg**2).mean()) > 0.04:
                    break
            spans.append((a, a + length))
        censored = cut(full, beat, spans)
        clip = full[30 * SR : 60 * SR]
        r1 = compare_arrays(censored, clip)
        r2 = compare_arrays(full, censored[30 * SR : 60 * SR])
        r3 = compare_arrays(full * 0.8, (clip * (1 + 0.05 * rng.standard_normal(clip.size))).astype(np.float32))
        assert r1["aligned"] and near(r1["missing"], spans) and not r1["extra"], (seed, r1)
        assert near(r2["extra"], spans) and not r2["missing"], (seed, r2)
        assert not r3["missing"] and not r3["extra"], (seed, r3)
    print("ok: восемь разных песен")

    print("все проверки прошли")


if __name__ == "__main__":
    main()

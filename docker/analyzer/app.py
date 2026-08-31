"""
Анализатор аудио для Sukify.

Отдаёт всё, что нужно редактору переходов: темп, сетку долей и тактов,
тональность в камелот-нотации и волну в трёх полосах — низ, середина и верх.
Каждая полоса приходит парой: среднее по корзине (тело волны) и максимум
(гребень). Так громкий «кирпичный» мастер не превращается в сплошной блок —
видно, чем именно он заполнен.

POST /analyze  multipart: file=<аудио>  → JSON
GET  /health                            → {"ok": true}
"""

import base64
import os
import tempfile

import numpy as np
from essentia.standard import (
    HighPass,
    KeyExtractor,
    LowPass,
    MonoLoader,
    RhythmExtractor2013,
)
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse

SAMPLE_RATE = 44100
DEFAULT_PEAKS = 2048
# Границы полос — классическое диджейское деление: бочка и бас, тело трека,
# тарелки и воздух.
LOW_HZ = 200.0
HIGH_HZ = 2000.0
# Дальше этого не читаем: анализировать десятиминутный трек целиком незачем,
# темп и тональность стабильны, а память экономим.
MAX_SECONDS = 900

app = FastAPI(title="Sukify analyzer")

# Камелот-круг: мажор — сторона B, минор — сторона A.
CAMELOT_MAJOR = {
    "C": "8B", "G": "9B", "D": "10B", "A": "11B", "E": "12B", "B": "1B",
    "F#": "2B", "C#": "3B", "G#": "4B", "D#": "5B", "A#": "6B", "F": "7B",
}
CAMELOT_MINOR = {
    "A": "8A", "E": "9A", "B": "10A", "F#": "11A", "C#": "12A", "G#": "1A",
    "D#": "2A", "A#": "3A", "F": "4A", "C": "5A", "G": "6A", "D": "7A",
}
# Essentia отдаёт и бемоли — приводим к диезам, ключи словарей только в них.
FLAT_TO_SHARP = {"Db": "C#", "Eb": "D#", "Gb": "F#", "Ab": "G#", "Bb": "A#"}


def to_camelot(key: str, scale: str) -> str | None:
    note = FLAT_TO_SHARP.get(key, key)
    table = CAMELOT_MINOR if scale == "minor" else CAMELOT_MAJOR
    return table.get(note)


def bucket_grid(samples: np.ndarray, buckets: int) -> np.ndarray:
    """Раскладывает сэмплы по корзинам ровной матрицей, хвост добивая нулями."""
    per = max(1, int(np.ceil(samples.size / buckets)))
    padded = np.zeros(per * buckets, dtype=np.float32)
    padded[: samples.size] = samples

    return padded.reshape(buckets, per)


def bucket_stats(samples: np.ndarray, buckets: int) -> tuple[np.ndarray, np.ndarray]:
    """
    Пара «тело и гребень» по корзинам: среднеквадратичное и максимум модуля.

    RMS отвечает за ощущаемую громкость и живо ходит даже у зажатого мастера,
    максимум — за короткие удары. Рисуем одно поверх другого.
    """
    if samples.size == 0:
        zeros = np.zeros(buckets, dtype=np.float32)
        return zeros, zeros.copy()

    grid = bucket_grid(np.asarray(samples, dtype=np.float32), buckets)
    rms = np.sqrt(np.mean(np.square(grid), axis=1))
    peak = np.abs(grid).max(axis=1)

    return rms, peak


def encode_peaks(grid: np.ndarray, top: float) -> str:
    """
    Значения в base64 — так в разы компактнее JSON-массива.

    Множитель общий для всех полос: нормируй каждую по себе — и тихий верх
    вырастет до высоты баса, а стопка полос перестанет что-либо значить.
    """
    if top <= 0:
        return base64.b64encode(bytes(len(grid))).decode()

    return base64.b64encode(np.clip(grid / top * 255.0, 0, 255).astype(np.uint8).tobytes()).decode()


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/analyze")
async def analyze(file: UploadFile = File(...), buckets: int = DEFAULT_PEAKS):
    buckets = max(64, min(buckets, 8192))
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="пустой файл")

    suffix = os.path.splitext(file.filename or "")[1] or ".mp3"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(raw)
        path = tmp.name

    try:
        audio = MonoLoader(filename=path, sampleRate=SAMPLE_RATE)()
    except Exception as e:  # noqa: BLE001 — наружу отдаём понятный текст
        os.unlink(path)
        raise HTTPException(status_code=422, detail=f"не смог прочитать аудио: {e}")

    try:
        limit = MAX_SECONDS * SAMPLE_RATE
        if audio.size > limit:
            audio = audio[:limit]

        duration_ms = int(round(audio.size / SAMPLE_RATE * 1000))

        # --- Темп и доли ---------------------------------------------------
        bpm, ticks, confidence, _estimates, _intervals = RhythmExtractor2013(
            method="multifeature"
        )(audio)
        beats_ms = [int(round(t * 1000)) for t in ticks]

        # --- Тональность ---------------------------------------------------
        key, scale, strength = KeyExtractor()(audio)

        # --- Волна: три полосы, у каждой тело и гребень --------------------
        low_band = LowPass(cutoffFrequency=LOW_HZ, sampleRate=SAMPLE_RATE)(audio)
        above_low = HighPass(cutoffFrequency=LOW_HZ, sampleRate=SAMPLE_RATE)(audio)
        mid_band = LowPass(cutoffFrequency=HIGH_HZ, sampleRate=SAMPLE_RATE)(above_low)
        high_band = HighPass(cutoffFrequency=HIGH_HZ, sampleRate=SAMPLE_RATE)(audio)

        low_rms, low_peak = bucket_stats(low_band, buckets)
        mid_rms, mid_peak = bucket_stats(mid_band, buckets)
        high_rms, high_peak = bucket_stats(high_band, buckets)

        # Полосы рисуются стопкой от середины, поэтому нормируем по самой
        # высокой сумме — иначе самый громкий такт вылезет за пределы панели.
        stack = low_peak + mid_peak + high_peak
        top = float(stack.max())

        # Прежние две полосы оставляем: их читают старые записи и мини-волна.
        _full_rms, full_peak = bucket_stats(np.asarray(audio), buckets)
        full_top = float(full_peak.max())

        return JSONResponse(
            {
                "duration_ms": duration_ms,
                "bpm": round(float(bpm), 2),
                "beat_confidence": round(float(confidence), 3),
                "beat_offset_ms": beats_ms[0] if beats_ms else 0,
                "beats_ms": beats_ms,
                "key": key,
                "scale": scale,
                "key_strength": round(float(strength), 3),
                "camelot": to_camelot(key, scale),
                "peaks_count": buckets,
                "peaks": {
                    "full": encode_peaks(full_peak, full_top),
                    "bass": encode_peaks(low_peak, full_top),
                },
                "bands": {
                    "lows": {"rms": encode_peaks(low_rms, top), "peak": encode_peaks(low_peak, top)},
                    "mids": {"rms": encode_peaks(mid_rms, top), "peak": encode_peaks(mid_peak, top)},
                    "highs": {"rms": encode_peaks(high_rms, top), "peak": encode_peaks(high_peak, top)},
                },
            }
        )
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass

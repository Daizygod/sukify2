"""
Анализатор аудио для Sukify.

Отдаёт всё, что нужно редактору переходов: темп, сетку долей и тактов,
тональность в камелот-нотации и пики волны в двух полосах — полной и
басовой (её Spotify рисует поверх основной вторым цветом).

POST /analyze  multipart: file=<аудио>  → JSON
GET  /health                            → {"ok": true}
"""

import base64
import os
import tempfile

import numpy as np
from essentia.standard import (
    KeyExtractor,
    LowPass,
    MonoLoader,
    RhythmExtractor2013,
)
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse

SAMPLE_RATE = 44100
DEFAULT_PEAKS = 2048
BASS_CUTOFF_HZ = 200.0
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


def peaks_of(samples: np.ndarray, buckets: int) -> str:
    """Пики по корзинам, 0..255, отдаём base64 — так в разы компактнее JSON-массива."""
    if samples.size == 0:
        return ""
    # Ровно buckets корзин: хвост добиваем нулями, чтобы reshape не ронял.
    per = max(1, int(np.ceil(samples.size / buckets)))
    padded = np.zeros(per * buckets, dtype=np.float32)
    padded[: samples.size] = np.abs(samples)
    grid = padded.reshape(buckets, per).max(axis=1)

    top = float(grid.max())
    if top <= 0:
        return base64.b64encode(bytes(buckets)).decode()
    scaled = np.clip(grid / top * 255.0, 0, 255).astype(np.uint8)

    return base64.b64encode(scaled.tobytes()).decode()


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

        # --- Пики: полная полоса и бас -------------------------------------
        bass = LowPass(cutoffFrequency=BASS_CUTOFF_HZ, sampleRate=SAMPLE_RATE)(audio)

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
                    "full": peaks_of(np.asarray(audio), buckets),
                    "bass": peaks_of(np.asarray(bass), buckets),
                },
            }
        )
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass

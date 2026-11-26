"""
Анализатор аудио для Sukify.

Отдаёт всё, что нужно редактору переходов: темп, сетку долей и тактов,
тональность в камелот-нотации и волну в трёх полосах — низ, середина и верх.
Каждая полоса приходит парой: среднее по корзине (тело волны) и максимум
(гребень). Так громкий «кирпичный» мастер не превращается в сплошной блок —
видно, чем именно он заполнен.

POST /analyze  multipart: file=<аудио>                 → JSON
POST /compare  multipart: full=<трек>, previews=<превью…> → JSON (не вырезаны ли слова)
GET  /health                                           → {"ok": true}
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


# --- Сверка с превью: не вырезаны ли слова -----------------------------------
#
# Цензура по закону о пропаганде наркотиков — это чаще всего заглушённый голос:
# бит играет, а слова нет. В полосе голоса (700–4500 Гц) такая дыра видна
# сразу, хотя по всей полосе записи почти не отличаются — бас всё забивает.
# Превью Deezer (30 с) находим внутри трека по огибающей, выравниваем громкость
# и ищем места, где одна запись заметно тише другой.

CMP_SR = 11025
CMP_WIN = 512  # 46 мс
CMP_HOP = 128  # 11,6 мс
VOICE_BANDS = [(700, 1100), (1100, 1700), (1700, 2600), (2600, 3500), (3500, 4500)]
EDGE_S = 1.0  # края превью — затухание, их не сравниваем
QUIETER_DB = 8.0  # настолько тише — уже не разница сведения, а нет голоса
MIN_RUN = 10  # ≈ 120 мс: короче — шум, а не вырезанное слово
MIN_ALIGN = 0.6  # ниже — превью не нашлось в треке: другая версия или песня
MAX_CMP_SECONDS = 900


def band_energies(x: np.ndarray) -> np.ndarray:
    """Энергия в полосах голоса по кадрам: матрица (кадры × полосы)."""
    if x.size < CMP_WIN:
        return np.zeros((0, len(VOICE_BANDS)), dtype=np.float32)

    frames_total = 1 + (x.size - CMP_WIN) // CMP_HOP
    window = np.hanning(CMP_WIN).astype(np.float32)
    freqs = np.fft.rfftfreq(CMP_WIN, 1.0 / CMP_SR)
    masks = [(freqs >= lo) & (freqs < hi) for lo, hi in VOICE_BANDS]
    out = np.empty((frames_total, len(VOICE_BANDS)), dtype=np.float32)

    # Кусками: трек на 10 минут целиком — это сотни мегабайт спектра.
    step = 4096
    offsets = np.arange(CMP_WIN)[None, :]
    for start in range(0, frames_total, step):
        count = min(step, frames_total - start)
        idx = (start + np.arange(count))[:, None] * CMP_HOP + offsets
        power = np.abs(np.fft.rfft(x[idx] * window, axis=1)) ** 2
        for b, mask in enumerate(masks):
            out[start : start + count, b] = power[:, mask].sum(axis=1)

    return out


def to_db(energy: np.ndarray) -> np.ndarray:
    """
    Децибелы с полом в −50 дБ от громких мест: паузы между словами у разных
    изданий — разный шум, и без пола он выглядел бы как огромная разница.
    """
    loud = float(np.percentile(energy, 99)) if energy.size else 0.0
    floor = max(loud * 1e-5, 1e-12)
    return 10.0 * np.log10(np.maximum(energy, floor))


def ncc_curve(full: np.ndarray, part: np.ndarray) -> np.ndarray | None:
    """Нормированная корреляция превью с каждым местом трека (по одному сигналу)."""
    n, m = full.size, part.size
    p = part - part.mean()
    p_norm = float(np.sqrt((p * p).sum()))
    if p_norm == 0:
        return None

    size = 1 << int(np.ceil(np.log2(n + m)))
    num = np.fft.irfft(np.fft.rfft(full, size) * np.conj(np.fft.rfft(p, size)), size)[: n - m + 1]

    # Нормируем на разброс того куска трека, с которым сравниваем.
    c1 = np.concatenate([[0.0], np.cumsum(full, dtype=np.float64)])
    c2 = np.concatenate([[0.0], np.cumsum(full.astype(np.float64) ** 2)])
    s1 = c1[m:] - c1[:-m]
    s2 = c2[m:] - c2[:-m]
    var = np.maximum(s2 - s1 * s1 / m, 1e-9)

    return num / (np.sqrt(var) * p_norm)


def align(full_bands: np.ndarray, part_bands: np.ndarray) -> tuple[int, float] | None:
    """
    Где в треке начинается превью: (кадр, корреляция 0…1).

    Сравниваем не уровень, а перепады энергии — атаки слогов и ударов — по
    каждой полосе голоса отдельно: бит в рэпе повторяется каждый такт, и по
    общей громкости превью «находится» в любом куплете.
    """
    n, m = full_bands.shape[0], part_bands.shape[0]
    if m < 50 or n < m:
        return None

    curves = []
    for b in range(full_bands.shape[1]):
        f = np.diff(to_db(full_bands[:, b]))
        p = np.diff(to_db(part_bands[:, b]))
        curve = ncc_curve(f, p)
        if curve is not None:
            curves.append(curve)
    if not curves:
        return None

    ncc = np.mean(curves, axis=0)
    k = int(np.argmax(ncc))
    return k, float(ncc[k])


def runs(mask: np.ndarray, min_len: int, gap: int = 18) -> list[tuple[int, int]]:
    """
    Участки, где mask держится: куски от 4 кадров, склеенные через паузы до
    gap кадров (между слогами голоса нет ни в одной записи — это не разница).
    Участок засчитываем, если в нём набралось min_len кадров.
    """
    pieces: list[list[int]] = []
    for i in np.flatnonzero(mask):
        i = int(i)
        if pieces and i - pieces[-1][1] <= 2:
            pieces[-1][1] = i
        else:
            pieces.append([i, i])
    pieces = [p for p in pieces if p[1] - p[0] + 1 >= 4]

    merged: list[list[int]] = []
    for s, e in pieces:
        if merged and s - merged[-1][1] <= gap:
            merged[-1][1] = e
            merged[-1][2] += e - s + 1
        else:
            merged.append([s, e, e - s + 1])

    return [(s, e + 1) for s, e, covered in merged if covered >= min_len]


def moving_median(x: np.ndarray, width: int = 5) -> np.ndarray:
    if x.size < width:
        return x
    pad = width // 2
    padded = np.pad(x, pad, mode="edge")
    windows = np.lib.stride_tricks.sliding_window_view(padded, width)
    return np.median(windows, axis=1)


def compare_arrays(full: np.ndarray, preview: np.ndarray) -> dict:
    """
    Сравнить трек с превью. Время — в миллисекундах от начала трека.

    missing — в треке голос тише, чем в превью (вырезано в треке),
    extra   — тише превью (вырезано в превью, а трек целый),
    differs — звучит иначе при той же громкости (переписано, задом наперёд).
    """
    fe = band_energies(full)
    pe = band_energies(preview)
    lf = to_db(fe.sum(axis=1))
    lp = to_db(pe.sum(axis=1))

    found = align(fe, pe)
    if found is None:
        return {"aligned": False, "score": 0.0, "note": "too_short"}
    k, score = found

    edge = int(EDGE_S * CMP_SR / CMP_HOP)
    t = np.arange(edge, lp.size - edge)
    if t.size < 100:
        return {"aligned": False, "score": round(score, 3), "note": "too_short"}

    a = lf[k + t]
    b = lp[t]
    diff = moving_median(a - b)
    diff = diff - np.median(diff)  # громкость мастеринга у изданий разная

    voiced_p = b > np.percentile(b, 30)
    voiced_f = a > np.percentile(a, 30)
    missing = (diff < -QUIETER_DB) & voiced_p
    extra = (diff > QUIETER_DB) & voiced_f

    # Форма спектра в полосе голоса: косинус между кадрами в логарифме.
    sa = np.log10(np.maximum(fe[k + t], fe.sum(axis=1).max() * 1e-6 + 1e-12))
    sb = np.log10(np.maximum(pe[t], pe.sum(axis=1).max() * 1e-6 + 1e-12))
    sa = sa - sa.mean(axis=1, keepdims=True)
    sb = sb - sb.mean(axis=1, keepdims=True)
    cos = (sa * sb).sum(axis=1) / (np.linalg.norm(sa, axis=1) * np.linalg.norm(sb, axis=1) + 1e-9)
    differs = (moving_median(cos) < 0.3) & voiced_p & ~missing & ~extra

    mismatch = float((np.abs(diff) > 6.0).mean())
    aligned = score >= MIN_ALIGN and mismatch < 0.35

    def ms(frame: int) -> int:
        return int(round((k + t[0] + frame) * CMP_HOP / CMP_SR * 1000))

    def spans(mask: np.ndarray) -> list[list[int]]:
        return [[ms(s), ms(e)] for s, e in runs(mask, MIN_RUN)]

    return {
        "aligned": bool(aligned),
        "score": round(score, 3),
        "offset_ms": int(round(k * CMP_HOP / CMP_SR * 1000)),
        "mismatch": round(mismatch, 3),
        "missing": spans(missing) if aligned else [],
        "extra": spans(extra) if aligned else [],
        "differs": spans(differs) if aligned else [],
    }


def load_mono(path: str, seconds: int) -> np.ndarray:
    audio = MonoLoader(filename=path, sampleRate=CMP_SR)()
    limit = seconds * CMP_SR
    return np.asarray(audio[:limit], dtype=np.float32)


async def save_upload(upload: UploadFile) -> str:
    raw = await upload.read()
    if not raw:
        raise HTTPException(status_code=400, detail=f"пустой файл {upload.filename}")
    suffix = os.path.splitext(upload.filename or "")[1] or ".mp3"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(raw)
        return tmp.name


@app.post("/compare")
async def compare(full: UploadFile = File(...), previews: list[UploadFile] = File(...)):
    paths: list[str] = []
    try:
        full_path = await save_upload(full)
        paths.append(full_path)
        try:
            track = load_mono(full_path, MAX_CMP_SECONDS)
        except Exception as e:  # noqa: BLE001
            raise HTTPException(status_code=422, detail=f"не смог прочитать трек: {e}")

        results = []
        for preview in previews:
            path = await save_upload(preview)
            paths.append(path)
            name = os.path.splitext(preview.filename or "")[0]
            try:
                clip = load_mono(path, 120)
            except Exception as e:  # noqa: BLE001
                results.append({"name": name, "aligned": False, "note": f"превью не читается: {e}"})
                continue
            results.append({"name": name, **compare_arrays(track, clip)})

        return JSONResponse({"results": results})
    finally:
        for p in paths:
            try:
                os.unlink(p)
            except OSError:
                pass


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

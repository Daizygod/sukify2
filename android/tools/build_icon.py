"""
Иконка приложения как в вебе (frontend/public/icon-512.png): зелёный круг
с чёрной «S». Букву берём контуром из заголовочного шрифта приложения, чтобы
она была ровно той же формы, что и в интерфейсе.

Пишет векторные drawable:
  ic_launcher_foreground.xml  — «S» по центру безопасной зоны 108×108
  ic_launcher_monochrome.xml  — то же для тематических иконок Android 13+
  ic_splash.xml               — зелёный круг с «S» для экрана запуска

Запуск (из корня репозитория, после build_fonts.py):
  docker run --rm -v "$PWD:/repo" -w /repo python:3.12-slim sh -c \
    "pip install -q fonttools && python -I android/tools/build_icon.py"
"""

import os

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

FONT = "android/app/src/main/res/font/sukify_title_black.ttf"
OUT = "android/app/src/main/res/drawable"
GREEN = "#FF1ED760"


def glyph_path(letter: str, box: float, center: float) -> str:
    """Контур буквы, вписанный в квадрат box с центром в center (ось Y вниз)."""
    font = TTFont(FONT)
    glyph_set = font.getGlyphSet()
    name = font.getBestCmap()[ord(letter)]

    bounds = BoundsPen(glyph_set)
    glyph_set[name].draw(bounds)
    x0, y0, x1, y1 = bounds.bounds
    scale = box / max(x1 - x0, y1 - y0)
    w, h = (x1 - x0) * scale, (y1 - y0) * scale

    # Шрифт рисует вверх, вектор Android — вниз: переворачиваем Y.
    dx = center - w / 2 - x0 * scale
    dy = center + h / 2 + y0 * scale
    svg = SVGPathPen(glyph_set, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
    glyph_set[name].draw(TransformPen(svg, (scale, 0, 0, -scale, dx, dy)))
    return svg.getCommands()


def vector(paths: list[tuple[str, str]], size: int = 108) -> str:
    items = "\n".join(
        f'    <path android:fillColor="{color}" android:pathData="{data}" />' for color, data in paths
    )
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Сгенерировано android/tools/build_icon.py — руками не править. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="{size}"
    android:viewportHeight="{size}">
{items}
</vector>
"""


def circle(cx: float, cy: float, r: float) -> str:
    return f"M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0z"


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    # Безопасная зона адаптивной иконки — круг диаметром 66 из 108.
    letter = glyph_path("S", 40, 54)
    with open(os.path.join(OUT, "ic_launcher_foreground.xml"), "w") as f:
        f.write(vector([("#FF000000", letter)]))
    with open(os.path.join(OUT, "ic_launcher_monochrome.xml"), "w") as f:
        f.write(vector([("#FFFFFFFF", letter)]))
    # Экран запуска: иконку маскируют кругом в 2/3 размера — круг 64 из 96.
    splash_letter = glyph_path("S", 30, 48)
    with open(os.path.join(OUT, "ic_splash.xml"), "w") as f:
        f.write(vector([(GREEN, circle(48, 48, 32)), ("#FF000000", splash_letter)], size=96))
    print("иконки готовы")


if __name__ == "__main__":
    main()

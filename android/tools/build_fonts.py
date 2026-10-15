"""
Шрифты приложения из шрифтов веба (frontend/public/fonts).

В вебе латиницу рисует SpotifyMixUI, а кириллицу — CircularSp-Cyrl: браузер
сам переходит к следующему шрифту в font-family, когда в первом нет символа.
Android так не умеет — недостающие глифы он берёт из системного Roboto.
Поэтому склеиваем пары заранее, по одной на начертание:

  sukify_regular.ttf      SpotifyMixUI-Regular   + CircularSp-Cyrl-400
  sukify_bold.ttf         SpotifyMixUI-Bold      + CircularSp-Cyrl-700
  sukify_title_bold.ttf   SpotifyMixUITitle@700  + CircularSp-Cyrl-700
  sukify_title_black.ttf  SpotifyMixUITitle@900  + CircularSp-Cyrl-700

Заголовочный шрифт вариативный (wght/wdth/opsz) — фиксируем нужные веса.

Запуск (из корня репозитория):
  docker run --rm -v "$PWD:/repo" -w /repo python:3.12-slim sh -c \
    "pip install -q fonttools brotli && python -I android/tools/build_fonts.py"
"""

import os
import sys
import tempfile

from fontTools import subset
from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

SRC = "frontend/public/fonts"
OUT = "android/app/src/main/res/font"


def clean(font: TTFont) -> TTFont:
    """
    Подготовка к склейке: без хинтинга и вертикальных метрик.

    Программы хинтинга у шрифтов разные и вместе работать не могут (а
    Android на плотных экранах их и не исполняет), vhea/vmtx есть только у
    латинского — склейщик требует одинакового набора полей.
    """
    options = subset.Options()
    options.hinting = False
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.glyph_names = True
    options.notdef_outline = True
    options.legacy_kern = True
    options.drop_tables += ["vhea", "vmtx", "hdmx", "gasp", "DSIG"]
    subsetter = subset.Subsetter(options)
    subsetter.populate(glyphs=font.getGlyphOrder())
    subsetter.subset(font)
    return font


def to_ttf(name: str, tmp: str) -> str:
    font = TTFont(os.path.join(SRC, name + ".woff2"))
    font.flavor = None
    path = os.path.join(tmp, name + ".ttf")
    clean(font).save(path)
    return path


def title_instance(src: str, weight: int, tmp: str) -> str:
    font = TTFont(src)
    static = instantiateVariableFont(font, {"wght": weight, "wdth": 100, "opsz": 48})
    path = os.path.join(tmp, f"title-{weight}.ttf")
    clean(static).save(path)
    return path


def rename(font: TTFont, family: str, style: str) -> None:
    full = f"{family} {style}"
    ps = f"{family.replace(' ', '')}-{style.replace(' ', '')}"
    for record in font["name"].names:
        value = {1: family, 2: style, 4: full, 6: ps, 16: family, 17: style}.get(record.nameID)
        if value is not None:
            record.string = value


def merge(latin: str, cyrillic: str, out_name: str, family: str, style: str) -> None:
    merged = Merger().merge([latin, cyrillic])
    rename(merged, family, style)
    path = os.path.join(OUT, out_name)
    merged.save(path)
    cmap = merged.getBestCmap()
    has_cyr = all(c in cmap for c in range(0x0410, 0x0450))
    has_lat = all(c in cmap for c in range(0x41, 0x5B))
    print(f"{out_name}: {len(merged.getGlyphOrder())} глифов, латиница={has_lat}, кириллица={has_cyr}")
    if not (has_cyr and has_lat):
        sys.exit(f"{out_name}: не хватает символов")


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        regular = to_ttf("SpotifyMixUI-Regular", tmp)
        bold = to_ttf("SpotifyMixUI-Bold", tmp)
        cyr400 = to_ttf("CircularSp-Cyrl-400-normal", tmp)
        cyr700 = to_ttf("CircularSp-Cyrl-700-normal", tmp)
        title = to_ttf("SpotifyMixUITitleVariable", tmp)

        merge(regular, cyr400, "sukify_regular.ttf", "Sukify Text", "Regular")
        merge(bold, cyr700, "sukify_bold.ttf", "Sukify Text", "Bold")
        merge(title_instance(title, 700, tmp), cyr700, "sukify_title_bold.ttf", "Sukify Title", "Bold")
        merge(title_instance(title, 900, tmp), cyr700, "sukify_title_black.ttf", "Sukify Title", "Black")


if __name__ == "__main__":
    main()

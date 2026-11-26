<?php

namespace Tests\Unit;

use App\Services\Bulk\ArchiveUnpacker;
use App\Services\Bulk\DiskSpace;
use App\Services\Bulk\Text;
use PHPUnit\Framework\TestCase;

/** Нормализация названий и путей для массовой загрузки. */
class BulkTextTest extends TestCase
{
    public function test_titles_are_compared_without_noise(): void
    {
        $this->assertSame('грустная история', Text::norm('Грустная  ИСТОРИЯ!'));
        $this->assertSame('ежик', Text::norm('Ёжик'));
        $this->assertSame('rather be', Text::core('Rather Be (feat. Jess Glynne) - The Magician Remix'));
        $this->assertSame('hotel california', Text::core('Hotel California - 2013 Remaster'));
        $this->assertSame('серпантин', Text::core('Серпантин feat. Oxxxymiron'));
        // Название целиком в скобках — не теряем его.
        $this->assertSame('интро', Text::core('[Интро]'));
        $this->assertSame('', Text::norm(''));
        $this->assertSame('#', Text::norm('#'));
    }

    public function test_variant_markers_tell_versions_apart(): void
    {
        $this->assertSame(['remix'], Text::variants('Серпантин (Ремикс)'));
        $this->assertSame(['live'], Text::variants('Song - Live at Wembley'));
        $this->assertSame(['instrumental'], Text::variants('Трек (минус)'));
        $this->assertSame([], Text::variants('Hotel California - 2013 Remaster'));
        // «Олива» — не «live»: границы слова и для кириллицы, и для латиницы.
        $this->assertSame([], Text::variants('Oliver'));
        $this->assertSame([], Text::variants('Демон'));
    }

    public function test_artist_credits_are_split_carefully(): void
    {
        $known = fn (string $name) => in_array(Text::norm($name), ['tyler the creator', 'ac dc', 'simon and garfunkel', 'markul'], true);

        $this->assertSame(['Markul', 'Oxxxymiron'], Text::splitArtists('Markul, Oxxxymiron', $known));
        $this->assertSame(['OG Buda', 'Платина'], Text::splitArtists('OG Buda х Платина', $known));
        $this->assertSame(['Скриптонит', '104'], Text::splitArtists('Скриптонит feat. 104', $known));
        $this->assertSame(['A', 'B'], Text::splitArtists('A; B', $known));
        $this->assertSame(['Tyler, The Creator'], Text::splitArtists('Tyler, The Creator', $known));
        $this->assertSame(['AC/DC'], Text::splitArtists('AC/DC', $known));
        $this->assertSame(['Simon & Garfunkel'], Text::splitArtists('Simon & Garfunkel', $known));
        // «&» режем, только если каталог узнаёт половину.
        $this->assertSame(['Markul', 'Кто-то'], Text::splitArtists('Markul & Кто-то', $known));
        $this->assertSame(['Чужой & Другой'], Text::splitArtists('Чужой & Другой', $known));
    }

    public function test_path_fills_missing_tags(): void
    {
        $this->assertSame(
            ['artist' => 'Markul', 'album' => 'Sense of Human', 'title' => 'Серпантин', 'track' => 3, 'artist_from' => 'folder'],
            Text::parsePath('Music/Markul/2019 - Sense of Human [FLAC]/03 - Серпантин.flac'),
        );
        $this->assertSame(
            ['artist' => 'Кино', 'album' => 'Группа крови', 'title' => 'Спокойная ночь', 'track' => 7, 'artist_from' => 'folder'],
            Text::parsePath('Кино - Группа крови (1988)/07. Спокойная ночь.mp3'),
        );
        $this->assertSame(
            ['artist' => 'madk1d', 'album' => null, 'title' => 'грустная история', 'track' => null, 'artist_from' => 'filename'],
            Text::parsePath('madk1d - грустная история.mp3'),
        );
        // «7 Rings» — название, а не седьмой трек.
        $this->assertSame('7 Rings', Text::parsePath('Ariana Grande/thank u, next/7 Rings.m4a')['title']);
        // «(Bitter Sweet)» — часть названия альбома, не пометка формата.
        $this->assertSame('Album (Bitter Sweet)', Text::parsePath('X/Album (Bitter Sweet)/01 - Y.mp3')['album']);
    }

    public function test_cp1251_tags_read_as_latin1_are_repaired(): void
    {
        $mojibake = mb_convert_encoding(iconv('UTF-8', 'Windows-1251', 'Кино - Группа крови'), 'UTF-8', 'ISO-8859-1');

        $this->assertSame('Кино - Группа крови', Text::fixMojibake($mojibake));
        $this->assertSame('Größe', Text::fixMojibake('Größe'));
        $this->assertSame('Beyoncé', Text::fixMojibake('Beyoncé'));
        $this->assertSame('DJ Смэш', Text::fixMojibake(mb_convert_encoding(iconv('UTF-8', 'Windows-1251', 'DJ Смэш'), 'UTF-8', 'ISO-8859-1')));
    }

    public function test_zip_names_without_utf8_flag_are_decoded(): void
    {
        $unpacker = new ArchiveUnpacker(new DiskSpace());

        // Проводник Windows: cp866 без пометки о кодировке.
        $this->assertSame('Кино/Спокойная ночь.mp3', $unpacker->decodeName(iconv('UTF-8', 'CP866', 'Кино/Спокойная ночь.mp3')));
        $this->assertSame('Кино/Спокойная ночь.mp3', $unpacker->decodeName(iconv('UTF-8', 'Windows-1251', 'Кино/Спокойная ночь.mp3')));
        $this->assertSame('Café/01.mp3', $unpacker->decodeName(iconv('UTF-8', 'CP437', 'Café/01.mp3')));
        $this->assertSame('уже utf-8.flac', $unpacker->decodeName('уже utf-8.flac'));
    }

    public function test_unsafe_and_junk_paths_are_dropped(): void
    {
        $unpacker = new ArchiveUnpacker(new DiskSpace());

        $this->assertSame('a/b.mp3', $unpacker->safePath('../../a/./b.mp3'));
        $this->assertSame('a/b.mp3', $unpacker->safePath('/a\\b.mp3'));
        $this->assertNull($unpacker->safePath('__MACOSX/a/._b.mp3'));
        $this->assertNull($unpacker->safePath('a/._b.mp3'));
        $this->assertNull($unpacker->safePath('a/Thumbs.db'));
        $this->assertNull($unpacker->safePath('../..'));
    }
}

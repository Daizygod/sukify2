package ru.nepalimsya.sukify.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {
    private val site = "https://sukify.nepalimsya.ru"

    @Test
    fun `страницы сайта открываются экранами приложения`() {
        assertEquals(ReleaseRoute("heroes-villains"), DeepLinks.route("$site/release/heroes-villains"))
        assertEquals(ArtistRoute("metro-boomin"), DeepLinks.route("$site/artist/metro-boomin"))
        assertEquals(DiscographyRoute("metro-boomin"), DeepLinks.route("$site/artist/metro-boomin/discography"))
        assertEquals(ArtistLikedRoute("metro-boomin"), DeepLinks.route("$site/artist/metro-boomin/liked"))
        assertEquals(ProfileRoute("daizygod"), DeepLinks.route("$site/user/daizygod"))
        assertEquals(MixRoute(3), DeepLinks.route("$site/mix/3"))
        assertEquals(LikedRoute, DeepLinks.route("$site/liked"))
        assertEquals(GenreRoute("хип-хоп"), DeepLinks.route("$site/genre/%D1%85%D0%B8%D0%BF-%D1%85%D0%BE%D0%BF"))
    }

    @Test
    fun `приглашение в совместный плейлист и код компьютера`() {
        assertEquals(PlaylistRoute(12, join = "abc"), DeepLinks.route("$site/playlist/12?join=abc"))
        assertEquals(PlaylistRoute(12), DeepLinks.route("$site/playlist/12"))
        assertEquals(LinkDeviceRoute("ABCD-1234"), DeepLinks.route("$site/link-device?code=ABCD-1234"))
        assertEquals(LinkDeviceRoute(null), DeepLinks.route("$site/link-device"))
    }

    @Test
    fun `чужие и непонятные адреса — мимо`() {
        assertNull(DeepLinks.route("https://example.com/release/x"))
        assertNull(DeepLinks.route("$site/settings"))
        assertNull(DeepLinks.route("$site/playlist/abc"))
        assertNull(DeepLinks.route("$site/"))
        assertNull(DeepLinks.route(null))
        assertNull(DeepLinks.route("не ссылка"))
    }
}

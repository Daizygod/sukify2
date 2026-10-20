package ru.nepalimsya.sukify.ui.icons

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgPathTest {

    @Test
    fun `compact arc flags are split from coordinates`() {
        // Начало пути heartFill из Icon.vue.
        assertEquals(
            "M 15.724 4.22 A 4.313 4.313 0 0 0 12.192 .814 a 4.269 4.269 0 0 0 -3.622 1.13",
            normalizePath("M15.724 4.22A4.313 4.313 0 0012.192.814a4.269 4.269 0 00-3.622 1.13"),
        )
    }

    @Test
    fun `flags followed by separators stay intact`() {
        assertEquals("a 8 8 0 1 0 0 16", normalizePath("a8 8 0 1 0 0 16"))
        assertEquals("A 8 8 0 0 1 8 0", normalizePath("A8,8,0,0,1,8,0"))
    }

    @Test
    fun `repeated arcs keep counting arguments`() {
        assertEquals(
            "a 6.5 6.5 0 1 1 13 0 6.5 6.5 0 0 1 -13 0",
            normalizePath("a6.5 6.5 0 1 1 13 0 6.5 6.5 0 0 1-13 0"),
        )
    }

    @Test
    fun `numbers without separators are split at sign and second dot`() {
        assertEquals("M 1.5 -2.5 .25 L 3e-2 4", normalizePath("M1.5-2.5.25L3e-2 4"))
    }

    @Test
    fun `relative move after close starts from the contour start`() {
        // Две полоски иконки очереди: «zm0-4.5» — от (15,15), а не от (15,13.5).
        val nodes = absolutePathNodes(addPathNodes(normalizePath("M15 15H1v-1.5h14zm0-4.5H1V9h14z")))
        assertEquals(
            listOf(
                PathNode.MoveTo(15f, 15f),
                PathNode.LineTo(1f, 15f),
                PathNode.LineTo(1f, 13.5f),
                PathNode.LineTo(15f, 13.5f),
                PathNode.Close,
                PathNode.MoveTo(15f, 10.5f),
                PathNode.LineTo(1f, 10.5f),
                PathNode.LineTo(1f, 9f),
                PathNode.LineTo(15f, 9f),
                PathNode.Close,
            ),
            nodes,
        )
    }

    @Test
    fun `arcs after close become absolute too`() {
        val relative = setOf(
            PathNode.RelativeMoveTo::class, PathNode.RelativeLineTo::class, PathNode.RelativeHorizontalTo::class,
            PathNode.RelativeVerticalTo::class, PathNode.RelativeCurveTo::class, PathNode.RelativeReflectiveCurveTo::class,
            PathNode.RelativeQuadTo::class, PathNode.RelativeReflectiveQuadTo::class, PathNode.RelativeArcTo::class,
        )
        val arc = absolutePathNodes(addPathNodes(normalizePath("M8 0a8 8 0 1 0 0 16zm1 1a1 1 0 0 1 2 0")))
        assertTrue(arc.none { it::class in relative })
        assertEquals(PathNode.ArcTo(1f, 1f, 0f, false, true, 11f, 1f), arc.last())
    }

    @Test
    fun `segment right after close starts from the contour start`() {
        assertEquals(
            listOf(
                PathNode.MoveTo(0f, 0f),
                PathNode.LineTo(2f, 0f),
                PathNode.LineTo(2f, 2f),
                PathNode.Close,
                PathNode.MoveTo(0f, 0f),
                PathNode.LineTo(1f, 1f),
            ),
            absolutePathNodes(addPathNodes(normalizePath("M0 0h2v2zl1 1"))),
        )
    }
}

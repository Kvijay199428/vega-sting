package com.vega.sting.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizingTest {

    @Test
    fun `floor height produces minimum sizes`() {
        val s = RecordingWidgetProvider.sizingForHeight(120f)
        assertEquals(6, s.rootPaddingDp)
        assertEquals(14f, s.titleSp, 0.01f)
        assertEquals(11f, s.buttonSp, 0.01f)
    }

    @Test
    fun `ceiling height produces maximum sizes`() {
        val s = RecordingWidgetProvider.sizingForHeight(240f)
        assertEquals(8, s.rootPaddingDp)
        assertEquals(20f, s.titleSp, 0.01f)
        assertEquals(16f, s.buttonSp, 0.01f)
    }

    @Test
    fun `below floor clamps to floor`() {
        val s = RecordingWidgetProvider.sizingForHeight(74f)
        assertEquals(6, s.rootPaddingDp)
        assertEquals(14f, s.titleSp, 0.01f)
        assertEquals(11f, s.buttonSp, 0.01f)
    }

    @Test
    fun `above ceiling clamps to ceiling`() {
        val s = RecordingWidgetProvider.sizingForHeight(400f)
        assertEquals(8, s.rootPaddingDp)
        assertEquals(20f, s.titleSp, 0.01f)
        assertEquals(16f, s.buttonSp, 0.01f)
    }

    @Test
    fun `midpoint interpolates linearly`() {
        val s = RecordingWidgetProvider.sizingForHeight(180f)
        assertEquals(7, s.rootPaddingDp)
        assertEquals(17f, s.titleSp, 0.01f)
        assertEquals(13.5f, s.buttonSp, 0.01f)
    }
}

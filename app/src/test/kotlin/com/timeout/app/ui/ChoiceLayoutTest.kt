package com.timeout.app.ui

import org.junit.Assert.*
import org.junit.Test

class ChoiceLayoutTest {
    @Test fun `five compact context choices fill a three and two layout`() {
        assertEquals(listOf(3, 2), choiceRowSizes(5, 328f, 96f, 8f))
    }
    @Test fun `four choices balance into two complete rows rather than three plus one`() {
        assertEquals(listOf(2, 2), choiceRowSizes(4, 328f, 96f, 8f))
    }
    @Test fun `large labels use fewer columns without placeholder gaps`() {
        assertEquals(listOf(2, 2, 1), choiceRowSizes(5, 328f, 152f, 8f))
        assertEquals(listOf(1, 1, 1, 1, 1), choiceRowSizes(5, 328f, 200f, 8f))
    }
    @Test fun `wide windows keep all context choices in one full row`() {
        assertEquals(listOf(5), choiceRowSizes(5, 760f, 100f, 8f))
    }
    @Test fun `empty and single choice groups have no phantom cells`() {
        assertTrue(choiceRowSizes(0, 328f, 96f, 8f).isEmpty())
        assertEquals(listOf(1), choiceRowSizes(1, 48f, 96f, 8f))
    }
    @Test fun `every choice appears once and row counts stay balanced at supported widths`() {
        for (count in 1..40) for (width in listOf(240f, 320f, 480f, 760f)) {
            val rows = choiceRowSizes(count, width, 104f, 8f)
            assertEquals(count, rows.sum())
            assertTrue(rows.all { it > 0 })
            assertTrue(rows.max() - rows.min() <= 1)
            assertTrue(rows.all { (width - (it - 1) * 8f) / it >= 104f })
        }
    }
}

package com.timeout.app.ui

import androidx.compose.material3.SnackbarDuration
import org.junit.Assert.*
import org.junit.Test

class FeedbackTest {
    @Test fun `feedback kinds have unambiguous visible labels`() {
        assertEquals(setOf("Done", "Needs attention", "Couldn't finish", "Note"), FeedbackKind.entries.map { it.label }.toSet())
    }

    @Test fun `errors remain available longer while success stays brief`() {
        assertEquals(SnackbarDuration.Long, FeedbackVisuals("failed", FeedbackKind.ERROR).duration)
        assertEquals(SnackbarDuration.Short, FeedbackVisuals("saved", FeedbackKind.SUCCESS).duration)
    }
}

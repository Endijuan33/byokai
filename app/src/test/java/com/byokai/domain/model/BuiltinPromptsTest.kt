package com.byokai.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the seeded prompt set: every builtin must be usable as-is
 * (non-blank title and content) and titles must be unique so the
 * picker never shows duplicates.
 */
class BuiltinPromptsTest {

    @Test
    fun `seed set has four entries`() {
        assertEquals(4, BuiltinPrompts.all.size)
    }

    @Test
    fun `every seed has non-blank title and content`() {
        for (seed in BuiltinPrompts.all) {
            assertTrue("blank title in $seed", seed.title.isNotBlank())
            assertTrue("blank content in $seed", seed.content.isNotBlank())
        }
    }

    @Test
    fun `seed titles are unique`() {
        val titles = BuiltinPrompts.all.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
    }
}

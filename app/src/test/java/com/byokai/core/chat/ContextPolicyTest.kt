package com.byokai.core.chat

import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextPolicy] truncation tests.
 *
 * The contract: request-side only (this object never touches
 * persistence), order preserved, system prompt always kept, newest
 * messages win, and at least the newest message is always sent.
 */
class ContextPolicyTest {

    private fun message(i: Int, size: Int = 100): ChatMessage =
        ChatMessage(
            role = if (i % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
            content = "m$i-" + "x".repeat(size),
        )

    @Test
    fun `empty history plans nothing`() {
        val plan = ContextPolicy.plan(emptyList(), systemPrompt = "sp", contextWindow = 8000)
        assertTrue(plan.messages.isEmpty())
        assertEquals(0, plan.droppedCount)
        assertTrue(plan.estimatedTokens > 0) // system prompt still counted
    }

    @Test
    fun `everything fits means nothing dropped`() {
        val messages = List(6) { message(it) }
        val plan = ContextPolicy.plan(messages, systemPrompt = "sp", contextWindow = 1_000_000)
        assertEquals(messages, plan.messages)
        assertEquals(0, plan.droppedCount)
    }

    @Test
    fun `truncation keeps newest and preserves order`() {
        // Each message ~ (100+~4 chars)/4 + 4 overhead ≈ 30 tokens.
        // Budget 100 - 2048 reserve < 0 → degenerate: newest only.
        val messages = List(10) { message(it) }
        val plan = ContextPolicy.plan(messages, systemPrompt = null, contextWindow = 100)
        assertEquals(listOf(messages.last()), plan.messages)
        assertEquals(9, plan.droppedCount)
    }

    @Test
    fun `known window keeps as many newest as fit`() {
        // ~30 tokens/message; window 2148 → budget 100 → 3 messages fit.
        val messages = List(10) { message(it) }
        val plan = ContextPolicy.plan(messages, systemPrompt = null, contextWindow = 2148)
        assertEquals(3, plan.messages.size)
        assertEquals(messages.takeLast(3), plan.messages)
        assertEquals(7, plan.droppedCount)
        // Oldest-first order preserved within the kept suffix.
        val indices = plan.messages.map { it.content.substringBefore("-") }
        assertEquals(listOf("m7", "m8", "m9"), indices)
    }

    @Test
    fun `system prompt tokens come out of the budget`() {
        val messages = List(10) { message(it) }
        val bigSystem = "s".repeat(400) // ~100 tokens + overhead
        val without = ContextPolicy.plan(messages, systemPrompt = null, contextWindow = 2148)
        val with = ContextPolicy.plan(messages, systemPrompt = bigSystem, contextWindow = 2148)
        assertTrue(
            "system prompt should reduce kept messages",
            with.messages.size < without.messages.size,
        )
        // The system prompt itself is never in the message list, so
        // "keeping" it is the caller's job — the plan only budgets it.
        assertTrue(with.messages.size >= 1)
    }

    @Test
    fun `unknown window falls back to message cap`() {
        val messages = List(120) { message(it, size = 10) }
        val plan = ContextPolicy.plan(messages, systemPrompt = null, contextWindow = null)
        assertEquals(ContextPolicy.DEFAULT_MAX_MESSAGES, plan.messages.size)
        assertEquals(messages.takeLast(50), plan.messages)
        assertEquals(70, plan.droppedCount)
    }

    @Test
    fun `unknown window falls back to char cap`() {
        // 60 messages × 3000 chars = 180k chars > 128k cap.
        val messages = List(60) { message(it, size = 3000) }
        val plan = ContextPolicy.plan(messages, systemPrompt = null, contextWindow = null)
        assertTrue(plan.messages.size < 60)
        assertTrue(plan.messages.size >= 1)
        // Newest-first capping keeps the tail.
        assertEquals(messages.last(), plan.messages.last())
        val totalChars = plan.messages.sumOf { it.content.length }
        assertTrue(totalChars <= ContextPolicy.DEFAULT_MAX_CHARS)
    }

    @Test
    fun `estimate in plan matches estimator`() {
        val messages = List(4) { message(it) }
        val plan = ContextPolicy.plan(messages, systemPrompt = "sp", contextWindow = 1_000_000)
        assertEquals(
            TokenEstimator.estimateRequestTokens(messages, "sp"),
            plan.estimatedTokens,
        )
    }
}

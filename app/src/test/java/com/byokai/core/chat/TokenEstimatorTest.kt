package com.byokai.core.chat

import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ImageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TokenEstimator] heuristic tests. These pin the documented
 * heuristic — they do NOT assert any provider's real tokenization,
 * which this estimator explicitly is not.
 */
class TokenEstimatorTest {

    @Test
    fun `empty text estimates zero`() {
        assertEquals(0, TokenEstimator.estimateTokens(""))
    }

    @Test
    fun `estimate is roughly chars divided by four, rounded up`() {
        assertEquals(1, TokenEstimator.estimateTokens("a"))
        assertEquals(1, TokenEstimator.estimateTokens("abcd"))
        assertEquals(2, TokenEstimator.estimateTokens("abcde"))
        assertEquals(25, TokenEstimator.estimateTokens("a".repeat(100)))
    }

    @Test
    fun `request estimate includes per-message overhead`() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "hi"),
            ChatMessage(ChatRole.ASSISTANT, "hello"),
        )
        val withoutSystem = TokenEstimator.estimateRequestTokens(messages, null)
        val withSystem = TokenEstimator.estimateRequestTokens(messages, "be nice")
        assertTrue(withSystem > withoutSystem)
        // 2 messages overhead + content, no system overhead when null.
        val expected = 2 * TokenEstimator.TOKENS_PER_MESSAGE_OVERHEAD +
            TokenEstimator.estimateTokens("hi") +
            TokenEstimator.estimateTokens("hello")
        assertEquals(expected, withoutSystem)
    }

    @Test
    fun `blank system prompt adds no tokens`() {
        val messages = listOf(ChatMessage(ChatRole.USER, "hi"))
        assertEquals(
            TokenEstimator.estimateRequestTokens(messages, null),
            TokenEstimator.estimateRequestTokens(messages, "   "),
        )
    }

    @Test
    fun `each attached image adds IMAGE_TOKEN_ESTIMATE`() {
        val attachment = ImageAttachment(
            id = "att-1",
            fileName = "photo.png",
            mimeType = "image/png",
            width = 64,
            height = 64,
        )
        val plain = listOf(ChatMessage(ChatRole.USER, "hi"))
        val withOneImage = listOf(
            ChatMessage(ChatRole.USER, "hi", listOf(attachment)),
        )
        val withTwoImages = listOf(
            ChatMessage(ChatRole.USER, "hi", listOf(attachment, attachment)),
        )
        val base = TokenEstimator.estimateRequestTokens(plain, null)
        assertEquals(base + TokenEstimator.IMAGE_TOKEN_ESTIMATE, TokenEstimator.estimateRequestTokens(withOneImage, null))
        assertEquals(base + 2 * TokenEstimator.IMAGE_TOKEN_ESTIMATE, TokenEstimator.estimateRequestTokens(withTwoImages, null))
    }
}

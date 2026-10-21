package com.byokai.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.byokai.core.ui.LinkSafety
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor

/**
 * Renders an assistant message as Markdown (Milestone 4B).
 *
 * Safety properties (verified against
 * `com.mikepenz:multiplatform-markdown-renderer-m3-android:0.46.0`
 * sources):
 * - **No raw HTML execution.** The renderer is pure Compose — there is
 *   no WebView and no HTML engine anywhere in the dependency. In
 *   `MarkdownElementInternal`, node types without a dedicated
 *   component (including `HTML_BLOCK`) fall through to the `else`
 *   branch, which renders their children as plain text. A
 *   `<script>` tag can therefore never run; at worst it shows as
 *   literal text.
 * - **Links open only on explicit tap, and only when safe.** Link taps
 *   flow through [SafeUriHandler], which drops anything failing
 *   [LinkSafety.isSafeUrl] (non-http(s) schemes, relative URLs).
 *   Nothing opens automatically.
 * - **Code blocks** render with a header row (language label + copy
 *   button) via [CodeBlockWithCopy]. Note: the renderer's default code
 *   block does NOT show this header — its internal `showHeader` flag
 *   defaults to `false` and is never enabled by the default
 *   components — so the header is wired explicitly here. Tapping the
 *   copy button copies the block's code to the clipboard; **tables**
 *   render via the renderer's table component. Syntax highlighting is
 *   intentionally not added — the renderer does not ship a
 *   highlighter, and bolting one on would be forced (per the milestone
 *   brief).
 *
 * Streaming tradeoff: parsing is keyed on [text] inside the
 * renderer's remembered state and runs asynchronously — rapid
 * streaming deltas are conflated rather than thrashing the parser.
 * Each distinct text still re-parses from scratch (no incremental
 * parse); for chat-sized messages this is unnoticeable, but if
 * profiling ever shows jank on very long outputs, the fix is to
 * switch to the renderer's `StreamingMarkdownState` chunk API rather
 * than re-parsing the accumulated string.
 *
 * @param onLinkClick invoked with the URL after it passed
 * [LinkSafety]; the caller opens it (e.g. via `ACTION_VIEW`).
 */
@Composable
fun AssistantMarkdownText(
    text: String,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = remember(onLinkClick) { SafeUriHandler(onLinkClick) }
    // Match the previous plain-Text bubble styling (onSurface on
    // surfaceContainerHigh); the renderer's default is onBackground.
    val colors = markdownColor(text = MaterialTheme.colorScheme.onSurface)
    // Custom code-block components: identical to the renderer's
    // defaults except the header row (language label + copy button)
    // is enabled. The default components leave it off.
    val components = remember {
        markdownComponents(
            codeFence = {
                MarkdownCodeFence(
                    it.content,
                    it.node,
                    style = it.typography.code,
                    block = { code, language, style ->
                        CodeBlockWithCopy(code, language, style)
                    },
                )
            },
            codeBlock = {
                MarkdownCodeBlock(
                    it.content,
                    it.node,
                    style = it.typography.code,
                    block = { code, language, style ->
                        CodeBlockWithCopy(code, language, style)
                    },
                )
            },
        )
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
            content = text,
            colors = colors,
            modifier = modifier,
            components = components,
        )
    }
}

/**
 * Code block with the header row enabled: language label on the left,
 * copy-to-clipboard button on the right (the renderer's own
 * `MarkdownCodeTopBar`, reached through the public
 * [MarkdownCodeBackground] with `showHeader = true`).
 *
 * Mirrors the renderer's default code block styling; only the header
 * differs.
 */
@Composable
private fun CodeBlockWithCopy(
    code: String,
    language: String?,
    style: TextStyle,
) {
    val colors = LocalMarkdownColors.current
    val dimens = LocalMarkdownDimens.current
    val padding = LocalMarkdownPadding.current
    MarkdownCodeBackground(
        color = colors.codeBackground,
        shape = RoundedCornerShape(dimens.codeBackgroundCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        showHeader = true,
        language = language,
        code = code,
    ) {
        Text(
            text = code,
            style = style.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(padding.codeBlock),
        )
    }
}

/**
 * [UriHandler] that only forwards taps for URLs passing
 * [LinkSafety.isSafeUrl]. Unsafe links are silently ignored — they
 * must never reach an `ACTION_VIEW` intent.
 */
private class SafeUriHandler(
    private val onSafeLink: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        if (LinkSafety.isSafeUrl(uri)) {
            onSafeLink(uri)
        }
    }
}

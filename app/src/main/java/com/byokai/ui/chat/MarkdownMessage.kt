package com.byokai.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.byokai.core.ui.LinkSafety
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
 * - **Code blocks** get the renderer's built-in copy button and
 *   language label; **tables** render via the renderer's table
 *   component. Syntax highlighting is intentionally not added — the
 *   renderer does not ship a highlighter, and bolting one on would be
 *   forced (per the milestone brief).
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
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
            content = text,
            colors = colors,
            modifier = modifier,
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

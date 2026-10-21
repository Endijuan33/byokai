package com.byokai.domain.model

/**
 * A reusable system-prompt template (Milestone 4C).
 *
 * Stored in the `prompts` table (schema v2). Built-in templates are
 * seeded by [com.byokai.domain.repository.PromptRepository.ensureSeeded]
 * only when the table is empty; the user can edit or delete them
 * afterwards like any other prompt.
 *
 * @property id stable UUID, primary key in storage.
 * @property title user-visible name shown in pickers and lists.
 * @property content the system-prompt text sent to the provider.
 * @property isBuiltin true for the seeded templates; informational
 * only — builtins are fully editable and deletable.
 * @property createdAt epoch millis when the prompt was created.
 * @property updatedAt epoch millis of the last edit.
 */
data class PromptTemplate(
    val id: String,
    val title: String,
    val content: String,
    val isBuiltin: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * The built-in prompt templates seeded on first use.
 *
 * Pure data (no Android dependencies) so the seed set is unit-tested:
 * every entry must have a non-blank title and content. IDs are
 * assigned at insert time; the seed carries only title + content.
 */
object BuiltinPrompts {

    data class Seed(val title: String, val content: String)

    val all: List<Seed> = listOf(
        Seed(
            title = "Concise Assistant",
            content = "You are a concise assistant. Answer directly and briefly, " +
                "avoiding unnecessary preamble or filler.",
        ),
        Seed(
            title = "Code Reviewer",
            content = "You are an expert code reviewer. Point out bugs, " +
                "security issues, and design smells, and suggest concrete " +
                "improvements with examples.",
        ),
        Seed(
            title = "ELI5 Explainer",
            content = "Explain everything like I'm five years old: simple " +
                "words, short sentences, and fun analogies. Avoid jargon " +
                "unless you explain it first.",
        ),
        Seed(
            title = "Socratic Tutor",
            content = "You are a Socratic tutor. Never give the answer " +
                "directly; ask guiding questions that lead me to discover " +
                "it myself, one step at a time.",
        ),
    )
}

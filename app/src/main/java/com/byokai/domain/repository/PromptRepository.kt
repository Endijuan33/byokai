package com.byokai.domain.repository

import com.byokai.domain.model.BuiltinPrompts
import com.byokai.domain.model.PromptTemplate
import kotlinx.coroutines.flow.Flow

/**
 * CRUD over reusable system-prompt templates (Milestone 4C).
 *
 * All mutating operations return [Result]: [Result.Success] on a
 * committed write, [Result.Error] wrapping the [Exception] otherwise.
 * Validation failures (blank title or content) are reported as
 * [Result.Error] with [IllegalArgumentException] — the UI surfaces
 * them without a crash.
 */
interface PromptRepository {

    /** All prompts, builtins first, then by `updatedAt` descending. */
    fun prompts(): Flow<List<PromptTemplate>>

    /** The prompt with [id], or null when it does not exist. */
    suspend fun get(id: String): PromptTemplate?

    /**
     * Inserts [BuiltinPrompts.all] when the table is empty; a no-op
     * otherwise. Called once per app start by the prompts UI — never
     * re-seeds, so user edits/deletions of builtins are permanent.
     */
    suspend fun ensureSeeded(): Result<Unit>

    /** Creates a user prompt with a fresh UUID id. */
    suspend fun create(title: String, content: String): Result<PromptTemplate>

    /** Replaces a prompt's title and content, bumping `updatedAt`. */
    suspend fun update(id: String, title: String, content: String): Result<Unit>

    /** Deletes a prompt (builtins included — deletion is permanent). */
    suspend fun delete(id: String): Result<Unit>
}

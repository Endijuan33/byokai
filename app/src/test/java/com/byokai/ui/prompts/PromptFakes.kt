package com.byokai.ui.prompts

import com.byokai.core.common.Result
import com.byokai.domain.model.BuiltinPrompts
import com.byokai.domain.model.PromptTemplate
import com.byokai.domain.repository.PromptRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory fake of [PromptRepository] for ViewModel tests.
 *
 * Mirrors the real contract: [ensureSeeded] inserts the builtin seeds
 * only when the store is empty, prompts are ordered builtins-first then
 * by `updatedAt` descending, and blank title/content writes fail with
 * [IllegalArgumentException]. [failWrites] flips every suspending write
 * to [Result.Error] to simulate IO failures.
 */
class FakePromptRepository : PromptRepository {

    private val prompts = mutableMapOf<String, PromptTemplate>()
    private val promptsFlow = MutableStateFlow<List<PromptTemplate>>(emptyList())

    /** Flip to make every suspending write return [Result.Error]. */
    var failWrites: Boolean = false

    var seedCount: Int = 0
        private set
    var createCount: Int = 0
        private set
    var deleteCount: Int = 0
        private set

    private var clock = 1_000L
    private fun tick(): Long = ++clock

    override fun prompts(): Flow<List<PromptTemplate>> = promptsFlow

    override suspend fun get(id: String): PromptTemplate? = prompts[id]

    override suspend fun ensureSeeded(): Result<Unit> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        if (prompts.isNotEmpty()) return Result.Success(Unit)
        seedCount++
        for (seed in BuiltinPrompts.all) {
            val now = tick()
            val prompt = PromptTemplate(
                id = UUID.randomUUID().toString(),
                title = seed.title,
                content = seed.content,
                isBuiltin = true,
                createdAt = now,
                updatedAt = now,
            )
            prompts[prompt.id] = prompt
        }
        emit()
        return Result.Success(Unit)
    }

    override suspend fun create(title: String, content: String): Result<PromptTemplate> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        if (title.isBlank() || content.isBlank()) {
            return Result.Error(IllegalArgumentException("blank title or content"))
        }
        createCount++
        val now = tick()
        val prompt = PromptTemplate(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            content = content.trim(),
            isBuiltin = false,
            createdAt = now,
            updatedAt = now,
        )
        prompts[prompt.id] = prompt
        emit()
        return Result.Success(prompt)
    }

    override suspend fun update(id: String, title: String, content: String): Result<Unit> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        if (title.isBlank() || content.isBlank()) {
            return Result.Error(IllegalArgumentException("blank title or content"))
        }
        prompts[id]?.let {
            prompts[id] = it.copy(title = title.trim(), content = content.trim(), updatedAt = tick())
            emit()
        }
        return Result.Success(Unit)
    }

    override suspend fun delete(id: String): Result<Unit> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        if (prompts.remove(id) != null) {
            deleteCount++
            emit()
        }
        return Result.Success(Unit)
    }

    private fun emit() {
        promptsFlow.update {
            prompts.values.sortedWith(
                compareByDescending<PromptTemplate> { it.isBuiltin }
                    .thenByDescending { it.updatedAt },
            )
        }
    }
}

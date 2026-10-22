package com.byokai.domain.repository

import com.byokai.domain.model.ImageAttachment
import java.io.File

/**
 * App-private storage for user-attached images (Phase 9B).
 *
 * Implementations keep files under the app's private files directory —
 * never on shared storage — so no other app and no backup transport
 * sees them (the database is already backup-excluded; attachment files
 * live under the same private tree).
 *
 * All reads/writes are suspending; [fileFor] is a pure path resolution
 * and never touches disk.
 */
interface AttachmentStorage {

    /**
     * Reads the stored bytes for [attachment], resolved by
     * [ImageAttachment.fileName]. Throws when the file is missing or
     * unreadable — callers surface a user-friendly error, never a
     * stack trace.
     */
    suspend fun readBytes(attachment: ImageAttachment): ByteArray

    /**
     * Resolves the on-disk [File] for [fileName] WITHOUT creating it.
     *
     * Rejects directory traversal (`/`, `\`, `..`): [fileName] must be a
     * bare file name, never a path.
     */
    fun fileFor(fileName: String): File

    /**
     * Deletes the stored file for [fileName]. Best-effort: never throws
     * for a missing file — callers wrap in `runCatching` anyway.
     */
    suspend fun delete(fileName: String)
}

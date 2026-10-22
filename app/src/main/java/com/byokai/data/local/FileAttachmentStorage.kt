package com.byokai.data.local

import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.repository.AttachmentStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AttachmentStorage] backed by a private directory under the app's
 * files dir (Phase 9B).
 *
 * Defense in depth on top of the caller-side sanitizers (export ZIP
 * entry filter, UUID-minted file names): [fileFor] rejects anything
 * that is not a bare file name, so a hostile [ImageAttachment.fileName]
 * can never escape [attachmentsDir].
 */
class FileAttachmentStorage(
    private val attachmentsDir: File,
) : AttachmentStorage {

    override suspend fun readBytes(attachment: ImageAttachment): ByteArray =
        withContext(Dispatchers.IO) {
            fileFor(attachment.fileName).readBytes()
        }

    override fun fileFor(fileName: String): File {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        require('/' !in fileName && '\\' !in fileName && fileName != "..") {
            "fileName must be a bare file name, got: $fileName"
        }
        return File(attachmentsDir, fileName)
    }

    override suspend fun delete(fileName: String) {
        withContext(Dispatchers.IO) {
            runCatching { fileFor(fileName).delete() }
        }
    }
}

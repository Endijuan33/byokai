package com.byokai.domain.repository

import com.byokai.domain.model.ImageAttachment

/**
 * Test fake for [AttachmentStorage]: returns pre-configured bytes for
 * every attachment and records which attachments were read. Runs on
 * the JVM — no device needed.
 */
class FakeAttachmentStorage(
    var bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
) : AttachmentStorage {

    val readCalls = mutableListOf<ImageAttachment>()
    val deletedFiles = mutableListOf<String>()

    override suspend fun readBytes(attachment: ImageAttachment): ByteArray {
        readCalls.add(attachment)
        return bytes
    }

    override fun fileFor(fileName: String): java.io.File =
        java.io.File(
            System.getProperty("java.io.tmpdir"),
            "byokai-test-attachments/$fileName",
        )

    override suspend fun delete(fileName: String) {
        deletedFiles.add(fileName)
    }
}

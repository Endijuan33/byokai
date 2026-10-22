package com.byokai.domain.model

/**
 * One user-attached image travelling with a chat turn (Phase 9B).
 *
 * The raw bytes live in [com.byokai.domain.repository.AttachmentStorage]
 * under [fileName]; this value is metadata only and is what gets
 * persisted in the `attachments` table, embedded in export payloads,
 * and handed to provider adapters (which read the bytes themselves at
 * send time).
 *
 * @property id stable UUID, primary key in storage.
 * @property fileName file name inside the attachment store, e.g.
 * `<uuid>.jpg`. Never a path — directory traversal is rejected by the
 * storage layer.
 * @property mimeType e.g. `image/jpeg`. The pipeline normalizes uploads
 * to JPEG (see [com.byokai.data.local.ImageProcessor]).
 * @property width pixel width of the STORED (downscaled) image.
 * @property height pixel height of the STORED (downscaled) image.
 */
data class ImageAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

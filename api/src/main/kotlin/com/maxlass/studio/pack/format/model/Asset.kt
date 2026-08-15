package com.maxlass.studio.pack.format.model

/** Marker interface for a pack asset (image or audio). */
interface Asset

enum class AssetType { AUDIO, IMAGE }

/**
 * An image asset.
 *
 * @property mimeType MIME type of [rawData] (image/bmp, image/png, image/jpeg, ...).
 * @property rawData Raw image bytes.
 * @property name Asset file name or address label.
 */
class ImageAsset(
    var mimeType: String?,
    var rawData: ByteArray?,
    var name: String?,
) : Asset

/**
 * An audio asset.
 *
 * @property mimeType MIME type of [rawData] (audio/x-wav, audio/mpeg, audio/ogg, ...).
 * @property rawData Raw audio bytes.
 * @property name Asset file name or address label.
 */
class AudioAsset(
    var mimeType: String?,
    var rawData: ByteArray?,
    var name: String?,
) : Asset

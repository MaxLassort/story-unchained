package com.maxlass.studio.pack.format.utils

/**
 * ID3 tag detection/removal for MP3 assets, mirroring `studio.core.v1.utils.ID3Tags`.
 */
object Id3Tags {

    private const val ID3_V1_SIZE = 128
    private const val ID3_V2_HEADER_SIZE = 10
    private const val ID3_V2_SIZE_OFFSET = 6

    fun hasId3v1Tag(mp3Data: ByteArray): Boolean =
        mp3Data.size >= ID3_V1_SIZE &&
            mp3Data[mp3Data.size - ID3_V1_SIZE] == 'T'.code.toByte() &&
            mp3Data[mp3Data.size - ID3_V1_SIZE + 1] == 'A'.code.toByte() &&
            mp3Data[mp3Data.size - ID3_V1_SIZE + 2] == 'G'.code.toByte()

    fun removeId3v1Tag(mp3Data: ByteArray): ByteArray =
        if (hasId3v1Tag(mp3Data)) mp3Data.copyOfRange(0, mp3Data.size - ID3_V1_SIZE) else mp3Data

    fun hasId3v2Tag(mp3Data: ByteArray): Boolean =
        mp3Data.size >= ID3_V2_HEADER_SIZE &&
            mp3Data[0] == 'I'.code.toByte() &&
            mp3Data[1] == 'D'.code.toByte() &&
            mp3Data[2] == '3'.code.toByte()

    fun removeId3v2Tag(mp3Data: ByteArray): ByteArray {
        if (!hasId3v2Tag(mp3Data)) return mp3Data
        val size =
            (mp3Data[ID3_V2_SIZE_OFFSET].toInt() and 0x7F shl 21) or
                (mp3Data[ID3_V2_SIZE_OFFSET + 1].toInt() and 0x7F shl 14) or
                (mp3Data[ID3_V2_SIZE_OFFSET + 2].toInt() and 0x7F shl 7) or
                (mp3Data[ID3_V2_SIZE_OFFSET + 3].toInt() and 0x7F)
        return mp3Data.copyOfRange(size + ID3_V2_HEADER_SIZE, mp3Data.size)
    }
}

package com.maxlass.studio.pack.format.utils

import de.sciss.jump3r.lowlevel.LameEncoder
import de.sciss.jump3r.mp3.MPEGMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Audio transcoding helpers, mirroring `studio.core.v1.utils.AudioConversion`.
 *
 * Decoding (OGG/MP3 -> WAV PCM) relies on the vorbisspi/mp3spi service providers and MP3
 * encoding on jump3r. OGG encoding is not supported: the `vorbis-java` encoder is not
 * available as a resolvable dependency (same as with the legacy studio-core POM).
 */
object AudioConversion {

    const val WAVE_SAMPLE_RATE = 32000f
    const val OGG_SAMPLE_RATE = 44100f
    const val MP3_SAMPLE_RATE = 44100f
    const val BITSIZE = 16
    const val MP3_BITSIZE = 32
    const val CHANNELS = 1

    fun oggToWave(oggData: ByteArray): ByteArray = anyToWave(oggData)

    fun mp3ToWave(mp3Data: ByteArray): ByteArray = anyToWave(mp3Data)

    /** Decodes any supported audio format into a mono 32 kHz PCM WAV. */
    fun anyToWave(data: ByteArray): ByteArray {
        val inputAudio = AudioSystem.getAudioInputStream(ByteArrayInputStream(data))
        val pcmFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            inputAudio.format.sampleRate,
            BITSIZE,
            inputAudio.format.channels,
            inputAudio.format.channels * 2,
            inputAudio.format.sampleRate,
            false,
        )
        val pcm = AudioSystem.getAudioInputStream(pcmFormat, inputAudio)
        val pcm32000Format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            WAVE_SAMPLE_RATE,
            BITSIZE,
            CHANNELS,
            CHANNELS * 2,
            WAVE_SAMPLE_RATE,
            false,
        )
        val pcm32000 = AudioSystem.getAudioInputStream(pcm32000Format, pcm)
        val baos = ByteArrayOutputStream()
        baos.writeBytes(pcm32000.readAllBytes())
        val waveStream = AudioInputStream(
            ByteArrayInputStream(baos.toByteArray()),
            pcm32000Format,
            baos.size().toLong(),
        )
        val output = ByteArrayOutputStream()
        AudioSystem.write(waveStream, AudioFileFormat.Type.WAVE, output)
        return output.toByteArray()
    }

    /**
     * WAV -> OGG. Unsupported: requires the `vorbis-java` encoder which is not resolvable
     * (the legacy studio-core POM referenced it with a broken `systemPath`).
     */
    fun waveToOgg(waveData: ByteArray): ByteArray =
        throw UnsupportedOperationException("OGG encoding is not supported: the vorbis-java encoder is not available")

    /** Encodes any supported audio format into a mono 44.1 kHz MP3 (jump3r / LAME). */
    fun anyToMp3(data: ByteArray): ByteArray {
        val inputAudio = AudioSystem.getAudioInputStream(ByteArrayInputStream(data))
        val pcmFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            inputAudio.format.sampleRate,
            BITSIZE,
            inputAudio.format.channels,
            inputAudio.format.channels * 2,
            inputAudio.format.sampleRate,
            false,
        )
        val pcm = AudioSystem.getAudioInputStream(pcmFormat, inputAudio)
        val pcmOverSampledFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            inputAudio.format.sampleRate * 2f,
            BITSIZE,
            CHANNELS,
            CHANNELS * 2,
            inputAudio.format.sampleRate * 2f,
            false,
        )
        val pcmOverSampled = AudioSystem.getAudioInputStream(pcmOverSampledFormat, pcm)
        val pcm44100Format = AudioFormat(
            AudioFormat.Encoding.PCM_FLOAT,
            MP3_SAMPLE_RATE,
            MP3_BITSIZE,
            CHANNELS,
            CHANNELS * 4,
            MP3_SAMPLE_RATE,
            false,
        )
        val pcm44100 = AudioSystem.getAudioInputStream(pcm44100Format, pcmOverSampled)
        // CBR 128 kbps mono: the Lunii decoder requires a minimum bitrate (it errors on the
        // low 32 kbps frames produced by jump3r's default VBR quality).
        val encoder = LameEncoder(pcm44100.format, 128, MPEGMode.MONO.ordinal, 4, false)
        val mp3 = ByteArrayOutputStream()
        val inputBuffer = ByteArray(encoder.pcmBufferSize)
        val outputBuffer = ByteArray(encoder.pcmBufferSize)
        while (true) {
            val bytesRead = pcm44100.read(inputBuffer)
            if (bytesRead <= 0) break
            val bytesWritten = encoder.encodeBuffer(inputBuffer, 0, bytesRead, outputBuffer)
            mp3.write(outputBuffer, 0, bytesWritten)
        }
        val bytesWritten = encoder.encodeFinish(outputBuffer)
        mp3.write(outputBuffer, 0, bytesWritten)
        encoder.close()
        return mp3.toByteArray()
    }
}

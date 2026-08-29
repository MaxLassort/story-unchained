/**
 * Converts a recorded audio Blob (WebM/Opus, MP4, etc.) into a 16-bit PCM WAV File.
 *
 * WAV is universally supported by the backend's Java `AudioSystem`, avoiding
 * "Stream of unsupported format" errors during Lunii pack format conversion.
 * The browser's `AudioContext.decodeAudioData()` can decode any format the
 * browser can play (WebM/Opus, MP4/AAC, OGG…), so we use it as a universal
 * decoder and then re-encode the raw PCM as a trivial WAV file.
 */

/**
 * Converts a recorded audio Blob into a WAV File.
 * Falls back to the original blob if decoding fails.
 */
export async function blobToWavFile(blob: Blob, filename = 'recording.wav'): Promise<File> {
  const arrayBuffer = await blob.arrayBuffer();
  const AudioContextCtor =
    window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
  const audioContext = new AudioContextCtor();
  try {
    const audioBuffer = await audioContext.decodeAudioData(arrayBuffer);
    const wavArrayBuffer = encodeWav(audioBuffer);
    return new File([wavArrayBuffer], filename, { type: 'audio/wav' });
  } catch {
    // Fallback: keep the original blob if the browser cannot decode it
    const ext = blob.type.includes('mp4') ? 'mp4' : 'webm';
    return new File([blob], filename.replace(/\.wav$/, `.${ext}`), { type: blob.type || 'audio/webm' });
  } finally {
    void audioContext.close();
  }
}

/**
 * Encodes an AudioBuffer as a 16-bit PCM mono WAV ArrayBuffer.
 */
function encodeWav(buffer: AudioBuffer): ArrayBuffer {
  const numChannels = 1; // mono
  const sampleRate = buffer.sampleRate;
  const bitsPerSample = 16;
  const bytesPerSample = bitsPerSample / 8;
  const blockAlign = numChannels * bytesPerSample;

  // Mix down to mono
  const length = buffer.length;
  const monoData = new Float32Array(length);
  for (let ch = 0; ch < buffer.numberOfChannels; ch++) {
    const channelData = buffer.getChannelData(ch);
    for (let i = 0; i < length; i++) {
      monoData[i] += channelData[i] / buffer.numberOfChannels;
    }
  }

  const dataSize = length * bytesPerSample;
  const bufferSize = 44 + dataSize;
  const arrayBuffer = new ArrayBuffer(bufferSize);
  const view = new DataView(arrayBuffer);

  // WAV header
  writeString(view, 0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  writeString(view, 8, 'WAVE');
  writeString(view, 12, 'fmt ');
  view.setUint32(16, 16, true); // PCM subchunk size
  view.setUint16(20, 1, true); // PCM format
  view.setUint16(22, numChannels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * blockAlign, true); // byte rate
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, bitsPerSample, true);
  writeString(view, 36, 'data');
  view.setUint32(40, dataSize, true);

  // Write PCM samples (16-bit signed, little-endian)
  let offset = 44;
  for (let i = 0; i < length; i++) {
    const sample = Math.max(-1, Math.min(1, monoData[i]));
    view.setInt16(offset, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
    offset += 2;
  }

  return arrayBuffer;
}

function writeString(view: DataView, offset: number, str: string): void {
  for (let i = 0; i < str.length; i++) {
    view.setUint8(offset + i, str.charCodeAt(i));
  }
}

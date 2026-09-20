/**
 * Client-side chapter split helpers: round cut points to the centisecond,
 * build section bounds from separators, and slice a decoded AudioBuffer / File
 * into mono WAV chapter files (compatible with the Lunii backend AudioSystem).
 */

import { encodeMonoPcmWav, mixToMono } from './wav-encoder.util';

/** Minimum gap between consecutive cut points / section edges (seconds). */
export const MIN_SECTION_GAP_S = 0.5;

/** Round a time in seconds to the nearest centisecond (0.01 s). */
export function roundToCentisecond(seconds: number): number {
  return Math.round(seconds * 100) / 100;
}

export interface SectionBound {
  /** Inclusive start time in seconds (centisecond-rounded). */
  start: number;
  /** Exclusive end time in seconds (centisecond-rounded). */
  end: number;
}

/** Optional content window — audio outside [start, end] is discarded when slicing. */
export interface SliceRange {
  start?: number;
  end?: number;
}

/**
 * Builds ordered section bounds from separator cut times.
 *
 * - Optional [range] trims the usable window (defaults to [0, duration]).
 * - Cuts are rounded to 0.01 s, clamped to (rangeStart, rangeEnd), sorted, and deduped.
 * - Cuts closer than [MIN_SECTION_GAP_S] to a neighbour or to range edges are dropped.
 * - Returns N+1 sections for N valid cuts (or a single [rangeStart, rangeEnd] when empty).
 */
export function buildSectionBounds(
  durationSeconds: number,
  cuts: number[],
  range?: SliceRange,
): SectionBound[] {
  const duration = roundToCentisecond(Math.max(0, durationSeconds));
  if (duration <= 0) return [];

  let rangeStart = roundToCentisecond(range?.start ?? 0);
  let rangeEnd = roundToCentisecond(range?.end ?? duration);
  rangeStart = Math.max(0, Math.min(rangeStart, duration));
  rangeEnd = Math.max(rangeStart, Math.min(rangeEnd, duration));

  if (rangeEnd - rangeStart < MIN_SECTION_GAP_S) {
    return [{ start: 0, end: duration }];
  }

  const sorted = [...new Set(cuts.map(roundToCentisecond))]
    .filter((t) => t > rangeStart && t < rangeEnd)
    .sort((a, b) => a - b);

  // Keep cuts greedily so a near-duplicate does not invalidate a valid earlier cut.
  const cleaned: number[] = [];
  for (const t of sorted) {
    const prev = cleaned.length === 0 ? rangeStart : cleaned[cleaned.length - 1]!;
    if (t - prev < MIN_SECTION_GAP_S) continue;
    if (rangeEnd - t < MIN_SECTION_GAP_S) continue;
    cleaned.push(t);
  }

  const edges = [rangeStart, ...cleaned, rangeEnd];
  const sections: SectionBound[] = [];
  for (let i = 0; i < edges.length - 1; i++) {
    const start = edges[i]!;
    const end = edges[i + 1]!;
    if (end - start >= MIN_SECTION_GAP_S) {
      sections.push({ start, end });
    }
  }
  return sections.length > 0 ? sections : [{ start: rangeStart, end: rangeEnd }];
}

/**
 * Slices a decoded AudioBuffer into mono WAV Files, one per section.
 * Sample ranges are derived from centisecond-rounded bounds at the buffer sample rate.
 */
export function sliceAudioBuffer(
  buffer: AudioBuffer,
  cuts: number[],
  filenamePrefix = 'chapitre',
  range?: SliceRange,
): File[] {
  const mono = mixToMono(buffer);
  const sampleRate = buffer.sampleRate;
  const duration = buffer.duration;
  const sections = buildSectionBounds(duration, cuts, range);

  return sections.map((section, index) => {
    const startSample = Math.max(0, Math.min(mono.length, Math.round(section.start * sampleRate)));
    const endSample = Math.max(startSample, Math.min(mono.length, Math.round(section.end * sampleRate)));
    const slice = mono.subarray(startSample, endSample);
    const wav = encodeMonoPcmWav(slice, sampleRate);
    const n = index + 1;
    return new File([wav], `${filenamePrefix}-${n}.wav`, { type: 'audio/wav' });
  });
}

/**
 * Decodes [file] with Web Audio, then slices it into mono WAV chapter files
 * at the given separator times (seconds). Throws if the browser cannot decode.
 */
export async function sliceAudioFile(
  file: File,
  cuts: number[],
  filenamePrefix = 'chapitre',
  range?: SliceRange,
): Promise<File[]> {
  const arrayBuffer = await file.arrayBuffer();
  const AudioContextCtor =
    window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
  const audioContext = new AudioContextCtor();
  try {
    // decodeAudioData may detach the buffer — copy first if the engine requires it.
    const audioBuffer = await audioContext.decodeAudioData(arrayBuffer.slice(0));
    return sliceAudioBuffer(audioBuffer, cuts, filenamePrefix, range);
  } finally {
    void audioContext.close();
  }
}

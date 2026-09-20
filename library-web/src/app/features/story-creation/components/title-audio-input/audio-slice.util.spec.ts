import { describe, it, expect } from 'vitest';
import {
  MIN_SECTION_GAP_S,
  buildSectionBounds,
  roundToCentisecond,
  sliceAudioBuffer,
} from './audio-slice.util';

function fakeAudioBuffer(options: {
  length?: number;
  sampleRate?: number;
  channels?: number;
}): AudioBuffer {
  const length = options.length ?? 44_100; // 1 s at 44.1 kHz
  const sampleRate = options.sampleRate ?? 44_100;
  const channels = options.channels ?? 1;
  const data = Array.from({ length: channels }, () => {
    const ch = new Float32Array(length);
    for (let i = 0; i < length; i++) ch[i] = i / length; // ramp — easy to verify slice bounds
    return ch;
  });
  return {
    length,
    sampleRate,
    numberOfChannels: channels,
    duration: length / sampleRate,
    getChannelData: (ch: number) => data[ch]!,
    copyFromChannel: () => undefined,
    copyToChannel: () => undefined,
  } as unknown as AudioBuffer;
}

describe('roundToCentisecond', () => {
  it('rounds to 0.01 s', () => {
    expect(roundToCentisecond(1.234)).toBe(1.23);
    expect(roundToCentisecond(1.235)).toBe(1.24);
    expect(roundToCentisecond(0.005)).toBe(0.01);
    expect(roundToCentisecond(0)).toBe(0);
  });
});

describe('buildSectionBounds', () => {
  it('returns a single section when there are no cuts', () => {
    expect(buildSectionBounds(10, [])).toEqual([{ start: 0, end: 10 }]);
  });

  it('splits on sorted separator times', () => {
    expect(buildSectionBounds(10, [3, 7])).toEqual([
      { start: 0, end: 3 },
      { start: 3, end: 7 },
      { start: 7, end: 10 },
    ]);
  });

  it('rounds cuts to centiseconds', () => {
    expect(buildSectionBounds(10, [2.346])).toEqual([
      { start: 0, end: 2.35 },
      { start: 2.35, end: 10 },
    ]);
  });

  it('drops cuts too close to edges or neighbours', () => {
    expect(buildSectionBounds(10, [0.2, 5, 9.8, 5.1])).toEqual([
      { start: 0, end: 5 },
      { start: 5, end: 10 },
    ]);
  });

  it('ignores cuts outside (0, duration)', () => {
    expect(buildSectionBounds(10, [-1, 0, 10, 12])).toEqual([{ start: 0, end: 10 }]);
  });

  it('trims to an explicit content range', () => {
    expect(buildSectionBounds(10, [5], { start: 2, end: 8 })).toEqual([
      { start: 2, end: 5 },
      { start: 5, end: 8 },
    ]);
  });

  it('drops cuts outside the content range', () => {
    expect(buildSectionBounds(10, [1, 5, 9], { start: 2, end: 8 })).toEqual([
      { start: 2, end: 5 },
      { start: 5, end: 8 },
    ]);
  });

  it('exposes the minimum gap constant used by the filter', () => {
    expect(MIN_SECTION_GAP_S).toBe(0.5);
  });
});

describe('sliceAudioBuffer', () => {
  it('produces one WAV file per section with expected sizes', () => {
    const sampleRate = 44_100;
    const buffer = fakeAudioBuffer({ length: sampleRate * 2, sampleRate }); // 2 s
    const cut = 0.8;
    const files = sliceAudioBuffer(buffer, [cut], 'chapitre');

    expect(files).toHaveLength(2);
    expect(files[0]!.name).toBe('chapitre-1.wav');
    expect(files[1]!.name).toBe('chapitre-2.wav');
    expect(files.every((f) => f.type === 'audio/wav')).toBe(true);

    // 16-bit mono: header 44 + samples * 2
    const samples0 = Math.round(cut * sampleRate);
    const samples1 = sampleRate * 2 - samples0;
    expect(files[0]!.size).toBe(44 + samples0 * 2);
    expect(files[1]!.size).toBe(44 + samples1 * 2);
  });

  it('returns a single file when cuts are empty', () => {
    const buffer = fakeAudioBuffer({ length: 22_050, sampleRate: 44_100 }); // 0.5 s
    const files = sliceAudioBuffer(buffer, []);
    expect(files).toHaveLength(1);
    expect(files[0]!.size).toBe(44 + 22_050 * 2);
  });
});

/**
 * Timecode helpers for `HH:MM:SS.cc` display and unit-aware stepping
 * (hours / minutes / seconds / centiseconds).
 */

import { roundToCentisecond } from './audio-slice.util';

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

/** Which field of `HH:MM:SS.cc` a caret/selection index maps to. */
export type TimecodeUnit = 'hours' | 'minutes' | 'seconds' | 'centiseconds';

/** Formats a duration as `HH:MM:SS.cc`. */
export function formatTimecode(seconds: number): string {
  const t = Math.max(0, roundToCentisecond(seconds));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const whole = Math.floor(t % 60);
  const cc = Math.round((t - Math.floor(t)) * 100);
  return `${pad2(h)}:${pad2(m)}:${pad2(whole)}.${pad2(cc)}`;
}

/** Parses `HH:MM:SS`, `HH:MM:SS.cc`, `MM:SS` or a plain seconds number. */
export function parseTimecode(raw: string): number | null {
  const text = raw.trim();
  if (!text) return null;
  if (/^\d+(\.\d+)?$/.test(text)) {
    const n = Number.parseFloat(text);
    return Number.isFinite(n) ? roundToCentisecond(n) : null;
  }
  const m = text.match(/^(\d+):(\d{1,2})(?::(\d{1,2}(?:\.\d+)?))?$/);
  if (!m) return null;
  const a = Number.parseInt(m[1]!, 10);
  const b = Number.parseInt(m[2]!, 10);
  if (m[3] != null) {
    const s = Number.parseFloat(m[3]);
    if (b >= 60 || s >= 60) return null;
    return roundToCentisecond(a * 3600 + b * 60 + s);
  }
  if (b >= 60) return null;
  return roundToCentisecond(a * 60 + b);
}

/** Map a caret index in `HH:MM:SS.cc` to the unit under (or just before) the caret. */
export function timecodeUnitAtIndex(index: number): TimecodeUnit {
  const i = Math.max(0, Math.min(10, index));
  if (i <= 2) return 'hours';
  if (i <= 5) return 'minutes';
  if (i <= 8) return 'seconds';
  return 'centiseconds';
}

/** Seconds delta for one step of a timecode unit. */
export function deltaSecondsForUnit(unit: TimecodeUnit): number {
  switch (unit) {
    case 'hours':
      return 3600;
    case 'minutes':
      return 60;
    case 'seconds':
      return 1;
    case 'centiseconds':
      return 0.01;
  }
}

/** `[start, end)` selection range covering a unit inside `HH:MM:SS.cc`. */
export function selectionRangeForUnit(unit: TimecodeUnit): readonly [number, number] {
  switch (unit) {
    case 'hours':
      return [0, 2];
    case 'minutes':
      return [3, 5];
    case 'seconds':
      return [6, 8];
    case 'centiseconds':
      return [9, 11];
  }
}

/** Unit under the input caret / selection (uses `selectionStart`). */
export function timecodeUnitFromInput(input: HTMLInputElement): TimecodeUnit {
  return timecodeUnitAtIndex(input.selectionStart ?? 0);
}

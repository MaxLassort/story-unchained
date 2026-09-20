import { describe, it, expect } from 'vitest';
import {
  deltaSecondsForUnit,
  formatTimecode,
  parseTimecode,
  selectionRangeForUnit,
  timecodeUnitAtIndex,
} from './timecode.util';

describe('formatTimecode / parseTimecode', () => {
  it('formats seconds as HH:MM:SS.cc', () => {
    expect(formatTimecode(0)).toBe('00:00:00.00');
    expect(formatTimecode(1.239)).toBe('00:00:01.24');
    expect(formatTimecode(42 * 60 + 52.1)).toBe('00:42:52.10');
    expect(formatTimecode(3661.5)).toBe('01:01:01.50');
  });

  it('parses HH:MM:SS(.cc) and MM:SS', () => {
    expect(parseTimecode('00:00:00.00')).toBe(0);
    expect(parseTimecode('00:42:52.10')).toBe(2572.1);
    expect(parseTimecode('1:01:01.5')).toBe(3661.5);
    expect(parseTimecode('42:52')).toBe(2572);
    expect(parseTimecode('12.5')).toBe(12.5);
    expect(parseTimecode('nope')).toBeNull();
  });
});

describe('timecode unit caret mapping', () => {
  it('maps caret indices in HH:MM:SS.cc to units', () => {
    expect(timecodeUnitAtIndex(0)).toBe('hours');
    expect(timecodeUnitAtIndex(2)).toBe('hours');
    expect(timecodeUnitAtIndex(3)).toBe('minutes');
    expect(timecodeUnitAtIndex(5)).toBe('minutes');
    expect(timecodeUnitAtIndex(6)).toBe('seconds');
    expect(timecodeUnitAtIndex(8)).toBe('seconds');
    expect(timecodeUnitAtIndex(9)).toBe('centiseconds');
    expect(timecodeUnitAtIndex(10)).toBe('centiseconds');
  });

  it('exposes step deltas and selection spans per unit', () => {
    expect(deltaSecondsForUnit('minutes')).toBe(60);
    expect(deltaSecondsForUnit('seconds')).toBe(1);
    expect(selectionRangeForUnit('minutes')).toEqual([3, 5]);
  });
});

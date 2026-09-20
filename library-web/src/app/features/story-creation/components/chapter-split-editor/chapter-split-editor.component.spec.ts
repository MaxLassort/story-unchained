import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ChapterSplitEditorComponent } from './chapter-split-editor.component';
import { formatTimecode } from '../title-audio-input/timecode.util';
import { LanguageService } from '../../../../core/services/language.service';

vi.mock('wavesurfer.js', () => {
  const create = vi.fn(() => ({
    registerPlugin: vi.fn((p: unknown) => p),
    on: vi.fn(),
    getDuration: vi.fn(() => 10),
    getCurrentTime: vi.fn(() => 3),
    play: vi.fn(() => Promise.resolve()),
    pause: vi.fn(),
    destroy: vi.fn(),
    setTime: vi.fn(),
    isPlaying: vi.fn(() => false),
  }));
  return { default: { create } };
});

vi.mock('wavesurfer.js/plugins/regions', () => {
  const create = vi.fn(() => ({
    on: vi.fn(),
    clearRegions: vi.fn(),
    addRegion: vi.fn((opts: { start: number; end?: number; id?: string }) => ({
      id: opts.id ?? 'r-0',
      start: opts.start,
      end: opts.end ?? opts.start,
    })),
    getRegions: vi.fn(() => []),
    destroy: vi.fn(),
  }));
  return { default: { create } };
});

describe('ChapterSplitEditorComponent', () => {
  let fixture: ComponentFixture<ChapterSplitEditorComponent>;
  const file = new File([new Uint8Array([1, 2, 3])], 'story.mp3', { type: 'audio/mpeg' });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChapterSplitEditorComponent],
      providers: [
        {
          provide: LanguageService,
          useValue: {
            currentLang: signal<'fr' | 'en'>('en'),
            isEnglish: signal(true),
            setLang: vi.fn(),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ChapterSplitEditorComponent);
    fixture.componentRef.setInput('file', file);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => {
    fixture?.destroy();
    TestBed.resetTestingModule();
  });

  it('starts without a confirmable split', () => {
    expect(fixture.componentInstance.canConfirm()).toBe(false);
  });

  it('emits cancelled when cancel is clicked', () => {
    const spy = vi.fn();
    fixture.componentInstance.cancelled.subscribe(spy);
    fixture.componentInstance.cancel();
    expect(spy).toHaveBeenCalledOnce();
  });

  it('formats times as HH:MM:SS.cc', () => {
    expect(fixture.componentInstance.formatTime(1.239)).toBe(formatTimecode(1.239));
    expect(fixture.componentInstance.formatTime(1.239)).toBe('00:00:01.24');
  });
});

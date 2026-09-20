import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { AudioUploadStepComponent } from './audio-upload-step.component';
import { ChaptersEditorState } from '../../chapters-editor-state.service';
import { StoryDraftService } from '../../../../core/services/story-draft.service';
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
  }));
  return { default: { create } };
});

vi.mock('wavesurfer.js/plugins/regions', () => {
  const create = vi.fn(() => ({
    on: vi.fn(),
    clearRegions: vi.fn(),
    addRegion: vi.fn((opts: { start: number; end?: number }) => ({
      id: 'r-0',
      start: opts.start,
      end: opts.end ?? opts.start,
    })),
    getRegions: vi.fn(() => []),
    destroy: vi.fn(),
  }));
  return { default: { create } };
});

describe('AudioUploadStepComponent', () => {
  let draftsMock: {
    ensureDraft: ReturnType<typeof vi.fn>;
    addDraftChapter: ReturnType<typeof vi.fn>;
    deleteDraftChapter: ReturnType<typeof vi.fn>;
    getCurrentDraft: ReturnType<typeof vi.fn>;
    uploadDraftFile: ReturnType<typeof vi.fn>;
    patchDraftNode: ReturnType<typeof vi.fn>;
    downloadDraftChapterTitleAudio: ReturnType<typeof vi.fn>;
    downloadDraftChapterNarration: ReturnType<typeof vi.fn>;
    downloadDraftChapterImage: ReturnType<typeof vi.fn>;
    draftId: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    draftsMock = {
      ensureDraft: vi.fn().mockResolvedValue('draft-1'),
      addDraftChapter: vi.fn().mockResolvedValue('chapter-uuid'),
      deleteDraftChapter: vi.fn().mockResolvedValue(undefined),
      getCurrentDraft: vi.fn().mockResolvedValue(null),
      uploadDraftFile: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      patchDraftNode: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      downloadDraftChapterTitleAudio: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'audio/mpeg' })),
      downloadDraftChapterNarration: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'audio/mpeg' })),
      downloadDraftChapterImage: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'image/png' })),
      draftId: vi.fn().mockReturnValue('draft-1'),
    };

    await TestBed.configureTestingModule({
      imports: [AudioUploadStepComponent],
      providers: [
        provideHttpClient(),
        { provide: StoryDraftService, useValue: draftsMock },
        ChaptersEditorState,
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
  });

  function createComponent() {
    const fixture = TestBed.createComponent(AudioUploadStepComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('starts with no staged chapters but is skippable (complete)', () => {
    const fixture = createComponent();
    expect(fixture.componentInstance.chapters().length).toBe(0);
    expect(fixture.componentInstance.complete()).toBe(true);
  });

  it('stages one pre-filled chapter per dropped audio file with the narration set', async () => {
    const fixture = createComponent();
    const file = new File(['audio'], 'intro.mp3', { type: 'audio/mpeg' });
    await fixture.componentInstance.addFiles([file]);
    fixture.detectChanges();

    const ch = fixture.componentInstance.chapters()[0];
    expect(ch.name).toBe('Chapitre 1');
    expect(ch.narrationFile).toBe(file);
    expect(ch.titleAudio).toBeNull();
    expect(ch.image).toEqual({ mode: 'number', iconId: null, file: null, chapterNumber: 1 });
    expect(fixture.componentInstance.chapters().length).toBe(1);
    expect(fixture.componentInstance.canSplit()).toBe(true);
    expect(fixture.componentInstance.complete()).toBe(true);
  });

  it('shows split only when exactly one narration file is staged', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([
      new File(['a'], 'a.mp3', { type: 'audio/mpeg' }),
      new File(['b'], 'b.mp3', { type: 'audio/mpeg' }),
    ]);
    fixture.detectChanges();
    expect(c.canSplit()).toBe(false);

    c.removeChapter(1);
    fixture.detectChanges();
    expect(c.canSplit()).toBe(true);
  });

  it('reorders staged chapters and updates number-mode images', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    const a = new File(['a'], 'a.mp3', { type: 'audio/mpeg' });
    const b = new File(['b'], 'b.mp3', { type: 'audio/mpeg' });
    await c.addFiles([a, b]);
    fixture.detectChanges();

    c.reorderChapters({ previousIndex: 0, currentIndex: 1 } as never);
    fixture.detectChanges();

    expect(c.chapters()[0].narrationFile).toBe(b);
    expect(c.chapters()[1].narrationFile).toBe(a);
    expect(c.chapters()[0].image?.chapterNumber).toBe(1);
    expect(c.chapters()[1].image?.chapterNumber).toBe(2);
  });

  it('opens and cancels the split editor', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([new File(['a'], 'a.mp3', { type: 'audio/mpeg' })]);
    c.startSplit();
    fixture.detectChanges();
    expect(c.splitting()).toBe(true);
    expect(c.complete()).toBe(false);

    c.cancelSplit();
    fixture.detectChanges();
    expect(c.splitting()).toBe(false);
    expect(c.complete()).toBe(true);
  });

  it('replaces the single chapter with sliced files on split confirm', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([new File(['a'], 'long.mp3', { type: 'audio/mpeg' })]);
    c.startSplit();
    const sliced = [
      new File(['1'], 'chapitre-1.wav', { type: 'audio/wav' }),
      new File(['2'], 'chapitre-2.wav', { type: 'audio/wav' }),
    ];
    await c.onSplitConfirmed(sliced);
    fixture.detectChanges();

    expect(c.splitting()).toBe(false);
    expect(c.chapters().length).toBe(2);
    expect(c.chapters()[0].narrationFile).toBe(sliced[0]);
    expect(c.chapters()[1].narrationFile).toBe(sliced[1]);
    expect(c.chapters()[0].name).toBe('Chapitre 1');
    expect(c.chapters()[1].name).toBe('Chapitre 2');
  });

  it('uses the uploaded TTS asset as mode audio when the pool is loaded', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    const titleAudio = new File(['x'], 'chapter-1.mp3', { type: 'audio/mpeg' });
    c.titleAudioPool.set(new Map([[1, titleAudio]]));
    await c.addFiles([new File(['a'], 'a.mp3', { type: 'audio/mpeg' })]);
    fixture.detectChanges();

    expect(c.chapters()[0].titleAudio?.mode).toBe('audio');
    expect(c.chapters()[0].titleAudio?.file).toBe(titleAudio);
  });

  it('continues chapter numbering across batches', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([
      new File(['a'], 'a.mp3', { type: 'audio/mpeg' }),
      new File(['b'], 'b.mp3', { type: 'audio/mpeg' }),
    ]);
    await c.addFiles([new File(['c'], 'c.mp3', { type: 'audio/mpeg' })]);
    fixture.detectChanges();

    expect(c.chapters().length).toBe(3);
    expect(c.chapters()[0].name).toBe('Chapitre 1');
    expect(c.chapters()[1].name).toBe('Chapitre 2');
    expect(c.chapters()[2].name).toBe('Chapitre 3');
  });

  it('rejects non-audio and oversized files', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([new File(['x'], 'note.txt', { type: 'text/plain' })]);
    fixture.detectChanges();
    expect(c.chapters().length).toBe(0);
    expect(c.typeError()).toContain('Only audio files up to 50 MB');
  });

  it('saves staged chapters to the draft via save()', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    const file = new File(['audio'], 'intro.mp3', { type: 'audio/mpeg' });
    await c.addFiles([file]);

    const ok = await c.save();
    expect(ok).toBe(true);
    expect(draftsMock.addDraftChapter).toHaveBeenCalledWith('draft-1', 'Chapitre 1');
    expect(draftsMock.uploadDraftFile).toHaveBeenCalled();
  });
});

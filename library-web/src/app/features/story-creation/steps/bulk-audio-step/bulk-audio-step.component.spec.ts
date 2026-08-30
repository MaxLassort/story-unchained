import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { BulkAudioStepComponent } from './bulk-audio-step.component';
import { ChaptersEditorState } from '../../chapters-editor-state.service';
import { StoryDraftService } from '../../../../core/services/story-draft.service';

describe('BulkAudioStepComponent', () => {
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
      imports: [BulkAudioStepComponent],
      providers: [
        provideHttpClient(),
        { provide: StoryDraftService, useValue: draftsMock },
        ChaptersEditorState,
      ],
    }).compileComponents();
  });

  function createComponent() {
    const fixture = TestBed.createComponent(BulkAudioStepComponent);
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
    // No TTS fallback: the title audio stays empty until the asset pool provides a file.
    expect(ch.titleAudio).toBeNull();
    expect(ch.image).toEqual({ mode: 'number', iconId: null, file: null, chapterNumber: 1 });
    expect(fixture.componentInstance.chapters().length).toBe(1);
    expect(fixture.componentInstance.complete()).toBe(true);
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

  it('removes a staged chapter', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([new File(['a'], 'a.mp3', { type: 'audio/mpeg' })]);
    await c.addFiles([new File(['b'], 'b.mp3', { type: 'audio/mpeg' })]);
    fixture.detectChanges();
    expect(c.chapters().length).toBe(2);

    c.removeChapter(0);
    fixture.detectChanges();
    expect(c.chapters().length).toBe(1);
    expect(c.chapters()[0].name).toBe('Chapitre 2');
  });

  it('rejects non-audio and oversized files', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([new File(['x'], 'note.txt', { type: 'text/plain' })]);
    fixture.detectChanges();
    expect(c.chapters().length).toBe(0);
    expect(c.typeError()).toContain('Only audio files up to 50 MB');
  });

  it('renames a staged chapter', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([new File(['a'], 'a.mp3', { type: 'audio/mpeg' })]);
    fixture.detectChanges();
    c.renameChapter(0, 'Ma première aventure');
    fixture.detectChanges();
    expect(c.chapters()[0].name).toBe('Ma première aventure');
  });

  it('loads chapters from an existing draft into the staging area', async () => {
    draftsMock.getCurrentDraft.mockResolvedValue({
      id: 'draft-1',
      title: 'Mon histoire',
      chapters: [
        {
          id: 'chap-1',
          name: 'Chapitre 1',
          hasTitleAudio: true,
          hasNarrationAudio: true,
          hasImage: true,
        },
      ],
    });

    const fixture = createComponent();
    const state = TestBed.inject(ChaptersEditorState);
    await state.loadExistingDraft();
    fixture.detectChanges();

    expect(fixture.componentInstance.chapters().length).toBe(1);
    expect(fixture.componentInstance.chapters()[0].id).toBe('chap-1');
    expect(fixture.componentInstance.chapters()[0].narrationFile).not.toBeNull();
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

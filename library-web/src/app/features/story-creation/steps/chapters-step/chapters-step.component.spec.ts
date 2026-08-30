import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { ChaptersStepComponent } from './chapters-step.component';
import { StoryDraftService } from '../../../../core/services/story-draft.service';
import { ChaptersEditorState } from '../../chapters-editor-state.service';

describe('ChaptersStepComponent', () => {
  let draftsMock: {
    ensureDraft: ReturnType<typeof vi.fn>;
    addDraftChapter: ReturnType<typeof vi.fn>;
    deleteDraftChapter: ReturnType<typeof vi.fn>;
    getCurrentDraft: ReturnType<typeof vi.fn>;
    setDraftChapterTitleText: ReturnType<typeof vi.fn>;
    uploadDraftChapterTitleAudio: ReturnType<typeof vi.fn>;
    uploadDraftChapterNarration: ReturnType<typeof vi.fn>;
    setDraftChapterIcon: ReturnType<typeof vi.fn>;
    uploadDraftChapterImage: ReturnType<typeof vi.fn>;
    downloadDraftChapterTitleAudio: ReturnType<typeof vi.fn>;
    downloadDraftChapterNarration: ReturnType<typeof vi.fn>;
    downloadDraftChapterImage: ReturnType<typeof vi.fn>;
    draftId: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    draftsMock = {
      ensureDraft: vi.fn().mockResolvedValue('draft-1'),
      addDraftChapter: vi.fn().mockResolvedValue('chapter-uuid'),
      deleteDraftChapter: vi.fn().mockResolvedValue(undefined),
      getCurrentDraft: vi.fn().mockResolvedValue(null),
      setDraftChapterTitleText: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      uploadDraftChapterTitleAudio: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      uploadDraftChapterNarration: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      setDraftChapterIcon: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      uploadDraftChapterImage: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      downloadDraftChapterTitleAudio: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'audio/mpeg' })),
      downloadDraftChapterNarration: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'audio/mpeg' })),
      downloadDraftChapterImage: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'image/png' })),
      draftId: vi.fn().mockReturnValue('draft-1'),
    };
  });

  async function createComponent() {
    await TestBed.configureTestingModule({
      imports: [ChaptersStepComponent],
      providers: [
        provideHttpClient(),
        { provide: StoryDraftService, useValue: draftsMock },
        ChaptersEditorState,
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ChaptersStepComponent);
    fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();
    return fixture;
  }

  it('starts with no chapters and is incomplete', async () => {
    const fixture = await createComponent();
    expect(fixture.componentInstance.chapters().length).toBe(0);
    expect(fixture.componentInstance.complete()).toBe(false);
  });

  it('adds a chapter on addChapter()', async () => {
    const fixture = await createComponent();
    fixture.componentInstance.addChapter();
    fixture.detectChanges();
    expect(fixture.componentInstance.chapters().length).toBe(1);
  });

  it('pre-fills an added chapter with title, uploaded TTS title audio, number image and no narration', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;
    const titleAudio = new File(['x'], 'chapter-1.mp3', { type: 'audio/mpeg' });
    component.titleAudioPool.set(new Map([[1, titleAudio]]));
    component.addChapter();
    fixture.detectChanges();
    const ch = component.chapters()[0];
    expect(ch.name).toBe('Chapitre 1');
    expect(ch.titleAudio?.mode).toBe('audio');
    expect(ch.titleAudio?.file).toBe(titleAudio);
    expect(ch.image).toEqual({ mode: 'number', iconId: null, file: null, chapterNumber: 1 });
    expect(ch.narrationFile).toBeNull();
    // Total pre-fill but for narration means the step stays incomplete.
    expect(component.complete()).toBe(false);
  });

  it('removes a chapter on removeChapter()', async () => {
    const fixture = await createComponent();
    fixture.componentInstance.addChapter();
    fixture.componentInstance.addChapter();
    fixture.detectChanges();
    expect(fixture.componentInstance.chapters().length).toBe(2);

    fixture.componentInstance.removeChapter(0);
    fixture.detectChanges();
    expect(fixture.componentInstance.chapters().length).toBe(1);
  });

  it('is complete when a chapter has name, title audio, narration and image', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.addChapter();
    component.model.update((m) => ({
      chapters: m.chapters.map((c) => ({
        ...c,
        name: 'Chapter 1',
        titleAudio: { mode: 'text', text: 'Chapter 1', file: null },
        narrationFile: new File(['x'], 'narration.mp3', { type: 'audio/mpeg' }),
        image: { mode: 'icon', iconId: 'star', file: null },
      })),
    }));
    fixture.detectChanges();

    expect(component.complete()).toBe(true);
  });

  it('is incomplete when a chapter is missing narration', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.addChapter();
    component.model.update((m) => ({
      chapters: m.chapters.map((c) => ({
        ...c,
        name: 'Chapter 1',
        titleAudio: { mode: 'text', text: 'Chapter 1', file: null },
        image: { mode: 'icon', iconId: 'star', file: null },
      })),
    }));
    fixture.detectChanges();

    expect(component.complete()).toBe(false);
  });

  it('creates and uploads all chapter data on save()', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const narration = new File(['x'], 'n.mp3', { type: 'audio/mpeg' });
    component.model.set({
      chapters: [
        {
          id: '',
          name: 'The Awakening',
          titleAudio: { mode: 'text', text: 'The Awakening', file: null },
          narrationFile: narration,
          image: { mode: 'icon', iconId: 'star', file: null },
        },
      ],
    });
    fixture.detectChanges();

    const ok = await component.save();
    expect(ok).toBe(true);
    expect(draftsMock.addDraftChapter).toHaveBeenCalledWith('draft-1', 'The Awakening');
    expect(draftsMock.setDraftChapterTitleText).toHaveBeenCalledWith('draft-1', 'chapter-uuid', 'The Awakening');
    expect(draftsMock.uploadDraftChapterNarration).toHaveBeenCalledWith('draft-1', 'chapter-uuid', narration);
    expect(draftsMock.setDraftChapterIcon).toHaveBeenCalledWith('draft-1', 'chapter-uuid', 'star');
    expect(component.model().chapters[0].id).toBe('chapter-uuid');
  });

  it('does not re-create chapters that already have an id', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.model.set({
      chapters: [
        {
          id: 'existing-id',
          name: 'Loaded Chapter',
          titleAudio: { mode: 'text', text: 'Loaded Chapter', file: null },
          narrationFile: new File(['x'], 'n.mp3', { type: 'audio/mpeg' }),
          image: { mode: 'image', iconId: null, file: new File(['x'], 'i.png', { type: 'image/png' }) },
        },
      ],
    });
    fixture.detectChanges();

    const ok = await component.save();
    expect(ok).toBe(true);
    expect(draftsMock.addDraftChapter).not.toHaveBeenCalled();
    expect(draftsMock.uploadDraftChapterNarration).toHaveBeenCalledWith('draft-1', 'existing-id', expect.any(File));
    expect(draftsMock.uploadDraftChapterImage).toHaveBeenCalledWith('draft-1', 'existing-id', expect.any(File));
  });

  it('returns false and sets error when save fails', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    draftsMock.addDraftChapter.mockRejectedValueOnce(new Error('boom'));
    component.model.set({
      chapters: [
        {
          id: '',
          name: 'Chapter 1',
          titleAudio: { mode: 'text', text: 'Chapter 1', file: null },
          narrationFile: new File(['x'], 'n.mp3', { type: 'audio/mpeg' }),
          image: { mode: 'icon', iconId: 'star', file: null },
        },
      ],
    });
    fixture.detectChanges();

    const ok = await component.save();
    expect(ok).toBe(false);
    expect(component.saveError()).toContain('Failed to save');
  });

  it('loads existing chapters from the draft on init', async () => {
    draftsMock.getCurrentDraft.mockResolvedValue({
      id: 'draft-1',
      chapters: [
        {
          id: 'c1',
          name: 'Loaded',
          titleText: 'Loaded Title',
          hasTitleAudio: false,
          titleAudioBytes: 0,
          hasNarrationAudio: true,
          narrationAudioBytes: 10,
          hasImage: false,
          imageBytes: 0,
          iconId: 'moon',
        },
      ],
    });

    const fixture = await createComponent();
    const component = fixture.componentInstance;

    expect(component.chapters().length).toBe(1);
    expect(component.chapters()[0].name).toBe('Loaded');
    expect(component.chapters()[0].id).toBe('c1');
    expect(component.chapters()[0].titleAudio?.mode).toBe('text');
    expect(component.chapters()[0].titleAudio?.text).toBe('Loaded Title');
    expect(component.chapters()[0].narrationFile).not.toBeNull();
    expect(component.chapters()[0].image?.mode).toBe('icon');
    expect(component.chapters()[0].image?.iconId).toBe('moon');
  });
});
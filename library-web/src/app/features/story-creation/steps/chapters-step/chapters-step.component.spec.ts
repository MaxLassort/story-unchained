import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { ChaptersStepComponent } from './chapters-step.component';
import { PacksService } from '../../../../core/services/packs.service';

describe('ChaptersStepComponent', () => {
  let packsMock: {
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
    packsMock = {
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
      providers: [provideHttpClient(), { provide: PacksService, useValue: packsMock }],
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
    expect(packsMock.addDraftChapter).toHaveBeenCalledWith('draft-1', 'The Awakening');
    expect(packsMock.setDraftChapterTitleText).toHaveBeenCalledWith('draft-1', 'chapter-uuid', 'The Awakening');
    expect(packsMock.uploadDraftChapterNarration).toHaveBeenCalledWith('draft-1', 'chapter-uuid', narration);
    expect(packsMock.setDraftChapterIcon).toHaveBeenCalledWith('draft-1', 'chapter-uuid', 'star');
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
    expect(packsMock.addDraftChapter).not.toHaveBeenCalled();
    expect(packsMock.uploadDraftChapterNarration).toHaveBeenCalledWith('draft-1', 'existing-id', expect.any(File));
    expect(packsMock.uploadDraftChapterImage).toHaveBeenCalledWith('draft-1', 'existing-id', expect.any(File));
  });

  it('returns false and sets error when save fails', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    packsMock.addDraftChapter.mockRejectedValueOnce(new Error('boom'));
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
    packsMock.getCurrentDraft.mockResolvedValue({
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
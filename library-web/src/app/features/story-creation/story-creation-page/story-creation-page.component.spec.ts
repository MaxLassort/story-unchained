import { describe, it, expect, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { signal, type WritableSignal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { RouterTestingModule } from '@angular/router/testing';
import { StoryCreationPageComponent } from './story-creation-page.component';
import { StoryDraftService } from '../../../core/services/story-draft.service';
import { LanguageService } from '../../../core/services/language.service';

describe('StoryCreationPageComponent', () => {
  async function setup() {
    const draftId: WritableSignal<string | null> = signal('draft-1');
    const draftsMock = {
      draftId,
      ensureDraft: vi.fn().mockImplementation(async () => {
        draftId.set('draft-1');
        return 'draft-1';
      }),
      getDraft: vi.fn().mockResolvedValue({
        id: 'draft-1',
        title: null,
        description: null,
        chapters: [],
        sourcePackId: null,
        hasThumbnail: false,
        hasCover: false,
        hasTitleAudio: false,
        hasMenuAudio: false,
      }),
      selectDraft: vi.fn(),
      createDraftFromPack: vi.fn().mockResolvedValue('draft-1'),
      finalizeDraft: vi.fn().mockResolvedValue({ packId: 'pack-1' }),
      listDrafts: vi.fn().mockResolvedValue([]),
      downloadDraftThumbnail: vi.fn(),
      downloadDraftCover: vi.fn(),
      downloadDraftTitleAudio: vi.fn(),
      downloadDraftMenuAudio: vi.fn(),
      downloadDraftChapterTitleAudio: vi.fn(),
      downloadDraftChapterNarration: vi.fn(),
      downloadDraftChapterImage: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [RouterTestingModule, StoryCreationPageComponent],
      providers: [
        provideHttpClient(),
        { provide: StoryDraftService, useValue: draftsMock },
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

    const fixture = TestBed.createComponent(StoryCreationPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.booting()).toBe(false));
    fixture.detectChanges();
    return fixture;
  }

  it('renders the wizard with a four-step linear stepper', async () => {
    const fixture = await setup();

    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('h1')?.textContent).toContain('Create a story');
    expect(root.querySelector('a')?.getAttribute('routerlink')).toBe('/packs');
    expect(root.querySelector('mat-stepper')).not.toBeNull();
    expect(root.querySelectorAll('mat-step-header').length).toBe(4);
  });

  it('disables the next action until the details step is complete', async () => {
    const fixture = await setup();

    const root: HTMLElement = fixture.nativeElement;
    const nextButton: HTMLButtonElement | null = root.querySelector(
      '.step-actions button[color="primary"]',
    );
    expect(nextButton).not.toBeNull();
    expect(nextButton!.disabled).toBe(true);

    const page = fixture.componentInstance;
    page.detailsStep()?.model.set({
      title: 'Mon histoire',
      description: '',
      titleAudio: { mode: 'text', text: 'Mon titre', file: null },
      menuAudio: { mode: 'text', text: 'Menu', file: null },
      thumbnail: new File(['x'], 'thumb.png', { type: 'image/png' }),
      cover: { mode: 'icon', iconId: 'star', file: null },
    });
    fixture.detectChanges();

    expect(nextButton!.disabled).toBe(false);
  });
});

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { StoryDetailsStepComponent } from './story-details-step.component';
import { StoryDraftService } from '../../../../core/services/story-draft.service';
import { StoryImageService } from '../../../../core/services/story-image.service';

describe('StoryDetailsStepComponent', () => {
  let draftsMock: {
    ensureDraft: ReturnType<typeof vi.fn>;
    uploadDraftThumbnail: ReturnType<typeof vi.fn>;
    uploadDraftCover: ReturnType<typeof vi.fn>;
    getCurrentDraft: ReturnType<typeof vi.fn>;
    downloadDraftThumbnail: ReturnType<typeof vi.fn>;
    downloadDraftCover: ReturnType<typeof vi.fn>;
    downloadDraftTitleAudio: ReturnType<typeof vi.fn>;
    updateDraftMetadata: ReturnType<typeof vi.fn>;
    uploadDraftTitleAudio: ReturnType<typeof vi.fn>;
    setDraftTitleText: ReturnType<typeof vi.fn>;
  };
  let imagesMock: {
    fetchIconPng: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    draftsMock = {
      ensureDraft: vi.fn().mockResolvedValue('draft-1'),
      uploadDraftThumbnail: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      uploadDraftCover: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      getCurrentDraft: vi.fn().mockResolvedValue(null),
      downloadDraftThumbnail: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'image/png' })),
      downloadDraftCover: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'image/png' })),
      downloadDraftTitleAudio: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'audio/mpeg' })),
      updateDraftMetadata: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      uploadDraftTitleAudio: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      setDraftTitleText: vi.fn().mockResolvedValue({ id: 'draft-1' }),
    };
    imagesMock = {
      fetchIconPng: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'image/png' })),
    };
  });

  async function createComponent() {
    await TestBed.configureTestingModule({
      imports: [StoryDetailsStepComponent],
      providers: [
        provideHttpClient(),
        { provide: StoryDraftService, useValue: draftsMock },
        { provide: StoryImageService, useValue: imagesMock },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(StoryDetailsStepComponent);
    fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();
    return fixture;
  }

  it('renders the title, description, title audio, menu audio, thumbnail and cover sections', async () => {
    const fixture = await createComponent();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('h2')?.textContent).toContain('Story Details');
    expect(root.querySelectorAll('mat-form-field').length).toBe(5);
    expect(root.querySelectorAll('app-title-audio-input').length).toBe(2);
    expect(root.querySelector('app-image-drop-input')).not.toBeNull();
    expect(root.querySelector('app-node-image-input')).not.toBeNull();
  });

  it('is incomplete without title and thumbnail', async () => {
    const fixture = await createComponent();
    expect(fixture.componentInstance.complete()).toBe(false);
  });

  it('is complete when title, thumbnail and cover are provided', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.model.set({
      title: 'Ma petite histoire',
      description: 'x',
      titleAudio: null,
      menuAudio: null,
      thumbnail: new File(['x'], 'thumb.png', { type: 'image/png' }),
      cover: { mode: 'icon', iconId: 'star', file: null },
    });
    fixture.detectChanges();

    expect(component.complete()).toBe(true);
  });

  it('saves all fields to the backend on save()', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const thumbFile = new File(['x'], 'thumb.png', { type: 'image/png' });
    component.model.set({
      title: 'Mon histoire',
      description: 'Une description',
      titleAudio: { mode: 'text', text: 'Mon titre', file: null },
      menuAudio: null,
      thumbnail: thumbFile,
      cover: { mode: 'image', iconId: null, file: new File(['x'], 'cover.png', { type: 'image/png' }) },
    });
    fixture.detectChanges();

    const ok = await component.save();
    expect(ok).toBe(true);
    expect(draftsMock.updateDraftMetadata).toHaveBeenCalledWith('draft-1', {
      title: 'Mon histoire',
      description: 'Une description',
    });
    expect(draftsMock.uploadDraftThumbnail).toHaveBeenCalledWith('draft-1', thumbFile);
    expect(draftsMock.uploadDraftCover).toHaveBeenCalled();
    expect(draftsMock.setDraftTitleText).toHaveBeenCalledWith('draft-1', 'Mon titre');
  });

  it('returns false and sets error when save fails', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    draftsMock.updateDraftMetadata.mockRejectedValueOnce(new Error('boom'));

    component.model.set({
      title: 'Mon histoire',
      description: '',
      titleAudio: null,
      menuAudio: null,
      thumbnail: new File(['x'], 'thumb.png', { type: 'image/png' }),
      cover: { mode: 'icon', iconId: 'star', file: null },
    });
    fixture.detectChanges();

    const ok = await component.save();
    expect(ok).toBe(false);
    expect(component.saveError()).toContain('Failed to save');
  });
});

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { StoryDetailsStepComponent } from './story-details-step.component';
import { PacksService } from '../../../../core/services/packs.service';

describe('StoryDetailsStepComponent', () => {
  let packsMock: {
    ensureDraft: ReturnType<typeof vi.fn>;
    uploadDraftThumbnail: ReturnType<typeof vi.fn>;
    uploadDraftCover: ReturnType<typeof vi.fn>;
    fetchIconPng: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    packsMock = {
      ensureDraft: vi.fn().mockResolvedValue('draft-1'),
      uploadDraftThumbnail: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      uploadDraftCover: vi.fn().mockResolvedValue({ id: 'draft-1' }),
      fetchIconPng: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'image/png' })),
    };
  });

  async function createComponent() {
    await TestBed.configureTestingModule({
      imports: [StoryDetailsStepComponent],
      providers: [provideHttpClient(), { provide: PacksService, useValue: packsMock }],
    }).compileComponents();
    const fixture = TestBed.createComponent(StoryDetailsStepComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders the title, description, title audio and thumbnail sections', async () => {
    const fixture = await createComponent();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('h2')?.textContent).toContain('Story Details');
    expect(root.querySelectorAll('mat-form-field').length).toBe(4);
    expect(root.querySelector('app-title-audio-input')).not.toBeNull();
    expect(root.querySelector('app-image-drop-input')).not.toBeNull();
    expect(root.querySelector('app-node-image-input')).not.toBeNull();
  });

  it('requires a non-blank title and a thumbnail for the step to be complete', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    expect(component.detailsForm().valid()).toBe(false);
    expect(component.complete()).toBe(false);

    component.model.set({ title: '', description: 'x', titleAudio: null, thumbnail: null, cover: null });
    fixture.detectChanges();
    expect(component.complete()).toBe(false);

    component.model.set({
      title: 'Ma petite histoire',
      description: 'x',
      titleAudio: null,
      thumbnail: new File(['x'], 'thumb.png', { type: 'image/png' }),
      cover: { mode: 'icon', iconId: 'star', file: null },
    });
    fixture.detectChanges();
    expect(component.complete()).toBe(true);
    expect(component.detailsForm().errors().length).toBe(0);
  });

  it('uploads the selected thumbnail to the draft', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const file = new File(['x'], 'thumb.png', { type: 'image/png' });
    component.model.set({ title: 'T', description: '', titleAudio: null, thumbnail: file, cover: null });
    fixture.detectChanges();
    await fixture.whenStable();

    expect(packsMock.ensureDraft).toHaveBeenCalled();
    expect(packsMock.uploadDraftThumbnail).toHaveBeenCalledWith('draft-1', file);
    expect(component.thumbnailError()).toBeNull();
  });

  it('surfaces an error when the thumbnail upload fails', async () => {
    packsMock.uploadDraftThumbnail.mockRejectedValue(new Error('boom'));
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.model.set({
      title: 'T',
      description: '',
      titleAudio: null,
      thumbnail: new File(['x'], 'thumb.png', { type: 'image/png' }),
      cover: null,
    });
    fixture.detectChanges();
    await vi.waitFor(() => expect(component.thumbnailError()).toContain('failed'));
  });
});
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { NodeImageInputComponent } from './node-image-input.component';
import { PacksService } from '../../../../core/services/packs.service';

describe('NodeImageInputComponent', () => {
  let packsMock: {
    renderSvg: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    packsMock = {
      renderSvg: vi.fn().mockResolvedValue(new Blob(['x'], { type: 'image/png' })),
    };
  });

  async function createComponent() {
    await TestBed.configureTestingModule({
      imports: [NodeImageInputComponent],
      providers: [provideHttpClient(), { provide: PacksService, useValue: packsMock }],
    }).compileComponents();
    const fixture = TestBed.createComponent(NodeImageInputComponent);
    fixture.componentInstance.value.set(null);
    fixture.detectChanges();
    return fixture;
  }

  it('defaults to icon mode', async () => {
    const fixture = await createComponent();
    expect(fixture.componentInstance.mode()).toBe('icon');
  });

  it('switches to image mode and keeps the previous icon selection', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.selectIcon('star');
    fixture.detectChanges();
    component.onModeChange('image');
    fixture.detectChanges();

    expect(component.mode()).toBe('image');
    expect(component.value()?.iconId).toBe('star');
  });

  it('selects an icon and stores it in the value', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.selectIcon('moon-star');
    fixture.detectChanges();

    expect(component.value()?.mode).toBe('icon');
    expect(component.value()?.iconId).toBe('moon-star');
  });

  it('builds the icon preview URL from the slug', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const url = component.iconPreviewSrc('star');
    expect(url).toContain('iconId=star');
  });

  it('rejects non-PNG/JPEG files on drop', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;
    component.onModeChange('image');
    fixture.detectChanges();

    const file = new File(['x'], 'pic.gif', { type: 'image/gif' });
    const event = { preventDefault: vi.fn(), dataTransfer: { files: [file] } } as unknown as DragEvent;
    component.onDrop(event);

    expect(component.value()?.file).toBeNull();
    expect(component.typeError()).toContain('PNG, JPEG or SVG');
  });

  it('tracks the dragging state on drag over and leave', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;
    component.onModeChange('image');
    fixture.detectChanges();

    component.onDragOver({ preventDefault: vi.fn() } as unknown as DragEvent);
    expect(component.dragging()).toBe(true);

    component.onDragLeave({ preventDefault: vi.fn() } as unknown as DragEvent);
    expect(component.dragging()).toBe(false);
  });

  it('clears the file and preview on remove', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;
    component.onModeChange('image');
    component.value.set({ mode: 'image', iconId: null, file: new File(['x'], 'p.png', { type: 'image/png' }) });
    fixture.detectChanges();

    component.clearFile();
    fixture.detectChanges();

    expect(component.value()?.file).toBeNull();
    expect(component.previewUrl()).toBeNull();
  });

  it('switches to number mode only when a chapter number is provided', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    fixture.componentRef.setInput('chapterNumber', 3);
    await component.selectChapterNumber();
    fixture.detectChanges();

    expect(component.value()?.mode).toBe('number');
    expect(component.value()?.chapterNumber).toBe(3);
  });

  it('preserves the icon selection when switching to number mode and back', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.selectIcon('star');
    fixture.detectChanges();

    fixture.componentRef.setInput('chapterNumber', 2);
    await component.selectChapterNumber();
    fixture.detectChanges();

    expect(component.value()?.mode).toBe('number');
    expect(component.value()?.iconId).toBe('star');

    component.onModeChange('icon');
    fixture.detectChanges();

    expect(component.mode()).toBe('icon');
    expect(component.value()?.iconId).toBe('star');
  });

  it('does not enter number mode when no chapter number is given', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.selectChapterNumber();
    fixture.detectChanges();

    expect(component.value()?.mode ?? 'icon').toBe('icon');
  });

  it('builds the chapter number preview URL from the number', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const url = component.chapterNumberPreviewSrc(7);
    expect(url).toContain('chapterNumber=7');
  });

  it('converts an SVG through the render endpoint and stores the PNG', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;
    component.onModeChange('image');
    fixture.detectChanges();

    const svg = new File(['<svg/>'], 'icon.svg', { type: 'image/svg+xml' });
    component.onFileSelected(svg);
    fixture.detectChanges();
    await vi.waitFor(() => expect(component.value()?.file).not.toBeNull());

    expect(packsMock.renderSvg).toHaveBeenCalledWith(svg);
    expect(component.value()?.mode).toBe('image');
    expect(component.value()?.file?.name).toBe('icon.png');
  });

  it('re-syncs the stored chapter number when the input changes in number mode', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    // Set up number mode with chapter 2.
    fixture.componentRef.setInput('chapterNumber', 2);
    await component.selectChapterNumber();
    fixture.detectChanges();
    expect(component.value()?.chapterNumber).toBe(2);

    // A preceding chapter is removed -> reindexed to 1.
    fixture.componentRef.setInput('chapterNumber', 1);
    fixture.detectChanges();
    await vi.waitFor(() => expect(component.value()?.chapterNumber).toBe(1));
  });

  it('normalizes the converted PNG filename for mime-detected / case-variant SVG', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;
    component.onModeChange('image');
    fixture.detectChanges();

    // "photo.png" detected as SVG by mime (no .svg in the name).
    component.onFileSelected(new File(['<svg/>'], 'photo.png', { type: 'image/svg+xml' }));
    fixture.detectChanges();
    await vi.waitFor(() => expect(component.value()?.file).not.toBeNull());
    expect(component.value()?.file?.name).toBe('photo.png');

    // Case variant "cat.SVG".
    component.onFileSelected(new File(['<svg/>'], 'cat.SVG', { type: 'image/svg+xml' }));
    fixture.detectChanges();
    await vi.waitFor(() => expect(component.value()?.file?.name).toBe('cat.png'));
  });

  it('surfaces an error when the SVG conversion fails', async () => {
    packsMock.renderSvg.mockRejectedValueOnce(new Error('boom'));
    const fixture = await createComponent();
    const component = fixture.componentInstance;
    component.onModeChange('image');
    fixture.detectChanges();

    const svg = new File(['<svg/>'], 'bad.svg', { type: 'image/svg+xml' });
    component.onFileSelected(svg);
    fixture.detectChanges();
    await vi.waitFor(() => expect(component.convertError()).not.toBeNull());

    expect(component.value()?.file).toBeNull();
    expect(component.convertError()).toContain('Could not convert');
  });
});

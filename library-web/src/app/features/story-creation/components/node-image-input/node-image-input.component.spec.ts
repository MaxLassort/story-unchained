import { describe, it, expect, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { NodeImageInputComponent } from './node-image-input.component';

describe('NodeImageInputComponent', () => {
  async function createComponent() {
    await TestBed.configureTestingModule({
      imports: [NodeImageInputComponent],
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
    expect(component.typeError()).toContain('PNG or JPEG');
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
});

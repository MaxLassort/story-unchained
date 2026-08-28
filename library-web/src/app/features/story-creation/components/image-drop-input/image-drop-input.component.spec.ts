import { describe, it, expect, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ImageDropInputComponent } from './image-drop-input.component';

describe('ImageDropInputComponent', () => {
  async function createComponent() {
    await TestBed.configureTestingModule({
      imports: [ImageDropInputComponent],
    }).compileComponents();
    const fixture = TestBed.createComponent(ImageDropInputComponent);
    fixture.componentInstance.value.set(null);
    fixture.detectChanges();
    return fixture;
  }

  it('stores the selected file in the model value and shows a preview', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const file = new File(['x'], 'thumb.png', { type: 'image/png' });
    component.onFileSelected(file);
    fixture.detectChanges();

    expect(component.value()).toBe(file);
    expect(component.fileName()).toBe('thumb.png');
    expect(component.previewUrl()).not.toBeNull();
  });

  it('clears the value and the preview on remove', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onFileSelected(new File(['x'], 'thumb.png', { type: 'image/png' }));
    fixture.detectChanges();
    component.clearFile();
    fixture.detectChanges();

    expect(component.value()).toBeNull();
    expect(component.previewUrl()).toBeNull();
    expect(component.fileName()).toBeNull();
  });

  it('keeps the preview in sync when the model value is written programmatically', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const file = new File(['x'], 'thumb.png', { type: 'image/png' });
    component.value.set(file);
    fixture.detectChanges();

    expect(component.previewUrl()).not.toBeNull();

    component.value.set(null);
    fixture.detectChanges();
    expect(component.previewUrl()).toBeNull();
  });

  it('accepts a dropped PNG/JPEG file and updates the value', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const file = new File(['x'], 'thumb.png', { type: 'image/png' });
    const event = { preventDefault: vi.fn(), dataTransfer: { files: [file] } } as unknown as DragEvent;
    component.onDrop(event);

    expect(component.value()).toBe(file);
    expect(component.dragging()).toBe(false);
    expect(event.preventDefault).toHaveBeenCalled();
  });

  it('rejects dropped files that are not PNG or JPEG', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const file = new File(['x'], 'thumb.gif', { type: 'image/gif' });
    const event = { preventDefault: vi.fn(), dataTransfer: { files: [file] } } as unknown as DragEvent;
    component.onDrop(event);

    expect(component.value()).toBeNull();
    expect(component.typeError()).toContain('PNG or JPEG');
  });

  it('tracks the dragging state on drag over and leave', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onDragOver({ preventDefault: vi.fn() } as unknown as DragEvent);
    expect(component.dragging()).toBe(true);

    component.onDragLeave({ preventDefault: vi.fn() } as unknown as DragEvent);
    expect(component.dragging()).toBe(false);
  });
});
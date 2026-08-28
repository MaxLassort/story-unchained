import { describe, it, expect, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { AudioDropInputComponent } from './audio-drop-input.component';

describe('AudioDropInputComponent', () => {
  async function createComponent() {
    await TestBed.configureTestingModule({
      imports: [AudioDropInputComponent],
    }).compileComponents();
    const fixture = TestBed.createComponent(AudioDropInputComponent);
    fixture.componentInstance.value.set(null);
    fixture.detectChanges();
    return fixture;
  }

  it('stores the selected audio file and shows a preview', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const file = new File(['x'], 'narration.mp3', { type: 'audio/mpeg' });
    component.onFileSelected(file);
    fixture.detectChanges();

    expect(component.value()).toBe(file);
    expect(component.fileName()).toBe('narration.mp3');
    expect(component.previewUrl()).not.toBeNull();
  });

  it('clears the value and preview on remove', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onFileSelected(new File(['x'], 'narration.mp3', { type: 'audio/mpeg' }));
    fixture.detectChanges();
    component.clearFile();
    fixture.detectChanges();

    expect(component.value()).toBeNull();
    expect(component.previewUrl()).toBeNull();
  });

  it('accepts a dropped audio file', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const file = new File(['x'], 'narration.mp3', { type: 'audio/mpeg' });
    const event = { preventDefault: vi.fn(), dataTransfer: { files: [file] } } as unknown as DragEvent;
    component.onDrop(event);

    expect(component.value()).toBe(file);
  });

  it('rejects dropped non-audio files', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const file = new File(['x'], 'doc.pdf', { type: 'application/pdf' });
    const event = { preventDefault: vi.fn(), dataTransfer: { files: [file] } } as unknown as DragEvent;
    component.onDrop(event);

    expect(component.value()).toBeNull();
    expect(component.typeError()).toContain('Audio file only');
  });
});

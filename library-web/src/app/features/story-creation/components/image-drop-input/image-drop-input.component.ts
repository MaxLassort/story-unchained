import { Component, computed, DestroyRef, effect, inject, input, model, signal, untracked } from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * Reusable image picker (Signal Forms `FormValueControl`): drag & drop zone with
 * click-to-browse fallback, PNG/JPEG only. The selected file lives in the model
 * value; the parent decides when to upload it. The preview is derived from the
 * value, so it stays in sync even when the model is written programmatically.
 */
@Component({
  selector: 'app-image-drop-input',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './image-drop-input.component.html',
  styleUrl: './image-drop-input.component.scss',
})
export class ImageDropInputComponent implements FormValueControl<File | null> {
  readonly title = input('Drag and drop image here or click to browse');
  readonly hint = input('PNG/JPEG only.');

  readonly value = model.required<File | null>();

  readonly dragging = signal(false);
  readonly typeError = signal<string | null>(null);
  readonly previewUrl = signal<string | null>(null);
  readonly fileName = computed(() => this.value()?.name ?? null);

  private previewedFile: File | null = null;
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    effect(() => {
      const file = this.value();
      untracked(() => this.setPreview(file));
    });
    this.destroyRef.onDestroy(() => this.setPreview(null));
  }

  onFileSelected(file: File | null): void {
    this.typeError.set(null);
    this.value.set(file);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0] ?? null;
    if (!file) return;
    if (!this.isImage(file)) {
      this.typeError.set('PNG or JPEG only.');
      return;
    }
    this.onFileSelected(file);
  }

  clearFile(): void {
    this.onFileSelected(null);
  }

  /** Keeps the preview object URL in sync with the model value (revokes the old one). */
  private setPreview(file: File | null): void {
    if (this.previewedFile === file) return;
    const url = this.previewUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.previewedFile = file;
    this.previewUrl.set(file ? URL.createObjectURL(file) : null);
  }

  private isImage(file: File): boolean {
    return file.type === 'image/png' || file.type === 'image/jpeg';
  }
}
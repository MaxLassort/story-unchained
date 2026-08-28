import { Component, computed, effect, input, model, signal, untracked } from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * Reusable audio picker (Signal Forms `FormValueControl`): drag & drop zone with
 * click-to-browse fallback, accepts any `audio/*` file. The selected file lives in
 * the model value; the parent decides when to upload it. The preview audio element
 * is derived from the value, so it stays in sync even when the model is written
 * programmatically.
 */
@Component({
  selector: 'app-audio-drop-input',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './audio-drop-input.component.html',
  styleUrl: './audio-drop-input.component.scss',
})
export class AudioDropInputComponent implements FormValueControl<File | null> {
  readonly title = input('Drag and drop audio here or click to browse');
  readonly hint = input('MP3, WAV, OGG…');

  readonly value = model.required<File | null>();

  readonly dragging = signal(false);
  readonly typeError = signal<string | null>(null);
  readonly previewUrl = signal<string | null>(null);
  readonly fileName = computed(() => this.value()?.name ?? null);

  private previewedFile: File | null = null;

  constructor() {
    effect(() => {
      const file = this.value();
      untracked(() => this.setPreview(file));
    });
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
    if (!this.isAudio(file)) {
      this.typeError.set('Audio file only (MP3, WAV, OGG…).');
      return;
    }
    this.onFileSelected(file);
  }

  clearFile(): void {
    this.onFileSelected(null);
  }

  private setPreview(file: File | null): void {
    if (this.previewedFile === file) return;
    const url = this.previewUrl();
    if (url) URL.revokeObjectURL(url);
    this.previewedFile = file;
    this.previewUrl.set(file ? URL.createObjectURL(file) : null);
  }

  private isAudio(file: File): boolean {
    return file.type.startsWith('audio/');
  }
}

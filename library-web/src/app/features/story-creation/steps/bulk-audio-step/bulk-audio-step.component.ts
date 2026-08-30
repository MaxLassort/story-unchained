import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { loadChapterTitleAudioPool, prefilledChapter } from '../../chapter-templates';
import { ChaptersEditorState } from '../../chapters-editor-state.service';

/** Maximum accepted size per audio file (50 MB), per the Bulk Upload mockup. */
const MAX_FILE_SIZE = 50 * 1024 * 1024;

/**
 * Bulk audio import step — the FIRST chapters step. Drop several narration audio files at once
 * and one pre-filled chapter is staged per file (1 file = 1 chapter). Each chapter is pre-filled
 * with a title ("Chapitre N"), a pre-rendered TTS title audio and a chapter-number image; only the
 * narration comes from the dropped file. Staged chapters are written to the shared chapters state
 * and shown / saved by the subsequent "Chapters" step.
 */
@Component({
  selector: 'app-bulk-audio-step',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTooltipModule,
  ],
  templateUrl: './bulk-audio-step.component.html',
  styleUrl: './bulk-audio-step.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BulkAudioStepComponent {
  private readonly chaptersState = inject(ChaptersEditorState);

  /** Chapters staged from the dropped audio files (shared with the Chapters step). */
  readonly staged = computed(() => this.chaptersState.model().chapters);
  readonly chapters = this.staged;

  /** Pre-rendered TTS title audio by chapter number, loaded from the static assets. */
  readonly titleAudioPool = signal<Map<number, File>>(new Map());
  private titleAudioPoolReady?: Promise<Map<number, File>>;

  readonly dragging = signal(false);
  readonly typeError = signal<string | null>(null);
  readonly loading = this.chaptersState.loading;
  readonly saving = this.chaptersState.saving;
  readonly saveError = this.chaptersState.saveError;

  readonly totalSizeLabel = computed(() => {
    const k = this.staged().length;
    if (k === 0) return '';
    return `${k} file${k > 1 ? 's' : ''} ready`;
  });

  /**
   * Whether the step is complete. Always true so the bulk step is optional (skippable): a user
   * who does not drop any file can still pass through and create chapters manually below.
   */
  readonly complete = computed(() => true);

  constructor() {
    // Pre-render the TTS title audio assets so staged chapters use the uploaded audio file
    // (mode 'audio') and finalize does not need to re-synthesise each chapter title via TTS.
    void this.ensureTitleAudioPool();
  }

  private ensureTitleAudioPool(): Promise<Map<number, File>> {
    if (!this.titleAudioPoolReady) {
      this.titleAudioPoolReady = loadChapterTitleAudioPool().then((loaded) => {
        // Prefer an already-populated pool (e.g. injected at test time), else use the loaded one.
        const current = this.titleAudioPool();
        if (current.size === 0) this.titleAudioPool.set(loaded);
        return current.size > 0 ? current : loaded;
      });
    }
    return this.titleAudioPoolReady;
  }

  async addFiles(files: File[]): Promise<void> {
    if (files.length === 0) return;
    this.typeError.set(null);

    const audio = files.filter((f) => f.type.startsWith('audio/') || /\.(mp3|wav|ogg|m4a|aac)$/i.test(f.name));
    const invalid = files.filter((f) => !audio.includes(f) || f.size > MAX_FILE_SIZE);
    if (invalid.length > 0) {
      this.typeError.set('Only audio files up to 50 MB are accepted (MP3, WAV, OGG…).');
    }

    const pool = await this.ensureTitleAudioPool();
    this.chaptersState.model.update((m) => {
      let next = m.chapters.length + 1;
      const additions = audio.map((file) => {
        const n = next++;
        const titleAudioFile = pool.get(n) ?? null;
        return { ...prefilledChapter(n, titleAudioFile), narrationFile: file };
      });
      return { chapters: [...m.chapters, ...additions] };
    });
  }

  renameChapter(index: number, name: string): void {
    this.chaptersState.model.update((m) => ({
      chapters: m.chapters.map((c, i) => (i === index ? { ...c, name } : c)),
    }));
  }

  removeChapter(index: number): void {
    this.chaptersState.deleteChapter(index);
  }

  clear(): void {
    this.chaptersState.clearChapters();
    this.typeError.set(null);
  }

  save(): Promise<boolean> {
    return this.chaptersState.save();
  }

  fileSizeLabel(file: File | null | undefined): string {
    if (!file) return '';
    const b = file.size;
    if (b >= 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`;
    if (b >= 1024) return `${Math.round(b / 1024)} KB`;
    return `${b} B`;
  }

  // --- drag & drop handlers (multiple files) ---

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
    this.addFiles(Array.from(event.dataTransfer?.files ?? []));
  }

  onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.addFiles(Array.from(input.files ?? []));
    input.value = '';
  }
}

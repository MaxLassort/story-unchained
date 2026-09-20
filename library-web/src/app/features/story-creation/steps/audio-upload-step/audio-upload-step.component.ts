import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDragPlaceholder, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { loadChapterTitleAudioPool, prefilledChapter } from '../../chapter-templates';
import { ChaptersEditorState } from '../../chapters-editor-state.service';
import { ChapterSplitEditorComponent } from '../../components/chapter-split-editor/chapter-split-editor.component';
import { TranslatePipe } from '../../../../core/pipes/translate.pipe';

/** Maximum accepted size per audio file (50 MB). */
const MAX_FILE_SIZE = 50 * 1024 * 1024;

/**
 * Audio upload step — drop one or more narration files.
 * Multiple files → one pre-filled chapter each.
 * Exactly one file → optional "Split into chapters" waveform editor.
 */
@Component({
  selector: 'app-audio-upload-step',
  imports: [
    CdkDrag,
    CdkDragHandle,
    CdkDragPlaceholder,
    CdkDropList,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTooltipModule,
    ChapterSplitEditorComponent,
    TranslatePipe,
  ],
  templateUrl: './audio-upload-step.component.html',
  styleUrl: './audio-upload-step.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AudioUploadStepComponent {
  private readonly chaptersState = inject(ChaptersEditorState);

  readonly staged = computed(() => this.chaptersState.model().chapters);
  readonly chapters = this.staged;

  readonly titleAudioPool = signal<Map<number, File>>(new Map());
  private titleAudioPoolReady?: Promise<Map<number, File>>;

  readonly dragging = signal(false);
  readonly typeError = signal<string | null>(null);
  /** When true, the single-file split editor is shown instead of the staging list. */
  readonly splitting = signal(false);

  readonly loading = this.chaptersState.loading;
  readonly saving = this.chaptersState.saving;
  readonly saveError = this.chaptersState.saveError;

  readonly totalSizeLabel = computed(() => {
    const k = this.staged().length;
    if (k === 0) return '';
    return `${k} file${k > 1 ? 's' : ''} ready`;
  });

  /** True when exactly one staged chapter has a narration file to split. */
  readonly canSplit = computed(() => {
    const list = this.staged();
    return list.length === 1 && list[0]?.narrationFile != null;
  });

  readonly singleNarrationFile = computed(() => this.staged()[0]?.narrationFile ?? null);

  /**
   * Skippable when not mid-split; while the split editor is open the step is incomplete
   * so the wizard cannot advance past an unfinished cut.
   */
  readonly complete = computed(() => !this.splitting());

  constructor() {
    void this.ensureTitleAudioPool();
  }

  private ensureTitleAudioPool(): Promise<Map<number, File>> {
    if (!this.titleAudioPoolReady) {
      this.titleAudioPoolReady = loadChapterTitleAudioPool().then((loaded) => {
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
    this.splitting.set(false);

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

  onChapterNameInput(index: number, event: Event): void {
    this.renameChapter(index, (event.target as HTMLInputElement).value);
  }

  /** Reorder staged chapters; keep number-mode images aligned with the new index. */
  reorderChapters(event: CdkDragDrop<unknown>): void {
    if (event.previousIndex === event.currentIndex) return;
    this.chaptersState.model.update((m) => {
      const chapters = [...m.chapters];
      moveItemInArray(chapters, event.previousIndex, event.currentIndex);
      return {
        chapters: chapters.map((ch, i) => {
          const n = i + 1;
          if (ch.image?.mode !== 'number') return ch;
          return { ...ch, image: { ...ch.image, chapterNumber: n } };
        }),
      };
    });
  }

  removeChapter(index: number): void {
    this.chaptersState.deleteChapter(index);
    if (!this.canSplit()) this.splitting.set(false);
  }

  clear(): void {
    this.chaptersState.clearChapters();
    this.typeError.set(null);
    this.splitting.set(false);
  }

  startSplit(): void {
    if (!this.canSplit()) return;
    this.splitting.set(true);
  }

  cancelSplit(): void {
    this.splitting.set(false);
  }

  async onSplitConfirmed(files: File[]): Promise<void> {
    if (files.length === 0) {
      this.splitting.set(false);
      return;
    }
    const pool = await this.ensureTitleAudioPool();
    this.chaptersState.clearChapters();
    this.chaptersState.model.set({
      chapters: files.map((file, i) => {
        const n = i + 1;
        return { ...prefilledChapter(n, pool.get(n) ?? null), narrationFile: file };
      }),
    });
    this.splitting.set(false);
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

import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { form, FormField, required, submit } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { StoryDraftService } from '../../../../core/services/story-draft.service';
import { StoryImageService } from '../../../../core/services/story-image.service';
import type { NodeImageSelection } from '../../../../core/models';
import { AudioDropInputComponent } from '../../components/audio-drop-input/audio-drop-input.component';
import { NodeImageInputComponent } from '../../components/node-image-input/node-image-input.component';
import { TitleAudioInputComponent, TitleAudioSelection } from '../../components/title-audio-input/title-audio-input.component';
import {
  ChapterFormModel,
  ChaptersFormModel,
  loadChapterTitleAudioPool,
  MAX_PRESET_TTS_CHAPTERS,
  prefilledChapter,
} from '../../chapter-templates';
import { ChaptersEditorState } from '../../chapters-editor-state.service';

@Component({
  selector: 'app-chapters-step',
  imports: [
    AudioDropInputComponent,
    FormField,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    NodeImageInputComponent,
    TitleAudioInputComponent,
  ],
  templateUrl: './chapters-step.component.html',
  styleUrl: './chapters-step.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChaptersStepComponent {
  private readonly chaptersState = inject(ChaptersEditorState);

  readonly model = this.chaptersState.model;

  readonly chaptersForm = form(this.model, (schemaPath) => {
    required(schemaPath.chapters, { message: 'At least one chapter is required' });
  });

  /** Whether the step is complete (drives the linear stepper). */
  readonly complete = computed(() => {
    const chapters = this.model().chapters;
    if (chapters.length === 0) return false;
    return chapters.every((ch) => {
      if (!ch.name.trim()) return false;
      if (!ch.titleAudio) return false;
      const hasTitle = ch.titleAudio.mode === 'text' ? ch.titleAudio.text.trim() !== '' : ch.titleAudio.file !== null;
      if (!hasTitle) return false;
      if (!ch.narrationFile) return false;
      if (!ch.image) return false;
      if (ch.image.mode === 'icon') return ch.image.iconId !== null;
      if (ch.image.mode === 'number') return ch.image.chapterNumber != null;
      return ch.image.file !== null;
    });
  });

  readonly chapters = computed(() => this.model().chapters);
  readonly loading = this.chaptersState.loading;
  readonly saving = this.chaptersState.saving;
  readonly saveError = this.chaptersState.saveError;

  /** Pre-rendered TTS title audio by chapter number, loaded from the static assets. */
  readonly titleAudioPool = signal<Map<number, File>>(new Map());

  constructor() {
    void this.loadTitleAudioPool();
  }

  /**
   * Loads the pre-rendered TTS title audio assets (`public/assets/title-tts/`) into
   * in-memory [File]s so a freshly added chapter can pre-select them as an uploaded
   * title audio. Missing assets are silently skipped (the TTS text fallback is used).
   */
  private async loadTitleAudioPool(): Promise<void> {
    if (this.titleAudioPool().size >= MAX_PRESET_TTS_CHAPTERS) return;
    const map = await loadChapterTitleAudioPool();
    // Only populate an still-empty pool so a pool injected/tested beforehand is not clobbered.
    if (this.titleAudioPool().size === 0) {
      this.titleAudioPool.set(map);
    }
  }

  /**
   * Adds a chapter pre-filled with the default title, a pre-selected title audio (uploaded
   * TTS asset) and the chapter-number image. Only the narration audio is left empty — that is
   * the piece the bulk-audio step (and the user) fills in.
   */
  addChapter(): void {
    const nextNumber = this.model().chapters.length + 1;
    const titleAudioFile = this.titleAudioPool().get(nextNumber) ?? null;
    this.model.update((m) => ({ chapters: [...m.chapters, prefilledChapter(nextNumber, titleAudioFile)] }));
  }

  removeChapter(index: number): void {
    this.chaptersState.deleteChapter(index);
  }

  async save(): Promise<boolean> {
    let result = false;
    await submit(this.chaptersForm, async () => {
      result = await this.chaptersState.save();
    });
    return result;
  }
}
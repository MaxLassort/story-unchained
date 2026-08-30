import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
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
  private readonly drafts = inject(StoryDraftService);
  private readonly images = inject(StoryImageService);
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
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  /** Pre-rendered TTS title audio by chapter number, loaded from the static assets. */
  readonly titleAudioPool = signal<Map<number, File>>(new Map());

  constructor() {
    void this.loadExistingDraft();
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
    const chapter = this.model().chapters[index];
    if (chapter?.id && this.drafts.draftId()) {
      void this.drafts
        .deleteDraftChapter(this.drafts.draftId() ?? '', chapter.id)
        .catch(() => {});
    }
    this.model.update((m) => ({
      chapters: m.chapters.filter((_, i) => i !== index),
    }));
  }

  async save(): Promise<boolean> {
    this.saveError.set(null);
    let result = false;
    await submit(this.chaptersForm, async () => {
      this.saving.set(true);
      try {
        const draftId = await this.drafts.ensureDraft();
        const chapters = this.model().chapters;

        for (const ch of chapters) {
          let chapterId = ch.id;
          if (!chapterId) {
            chapterId = await this.drafts.addDraftChapter(draftId, ch.name);
            this.model.update((m) => ({
              chapters: m.chapters.map((c) => (c === ch ? { ...c, id: chapterId } : c)),
            }));
          }

          if (ch.titleAudio) {
            if (ch.titleAudio.mode === 'text' && ch.titleAudio.text.trim()) {
              await this.drafts.setDraftChapterTitleText(draftId, chapterId, ch.titleAudio.text.trim());
            } else if (ch.titleAudio.mode === 'audio' && ch.titleAudio.file) {
              await this.drafts.uploadDraftChapterTitleAudio(draftId, chapterId, ch.titleAudio.file);
            }
          }

          if (ch.narrationFile) {
            await this.drafts.uploadDraftChapterNarration(draftId, chapterId, ch.narrationFile);
          }

          if (ch.image) {
            if (ch.image.mode === 'icon' && ch.image.iconId) {
              await this.drafts.setDraftChapterIcon(draftId, chapterId, ch.image.iconId);
            } else if (ch.image.mode === 'image' && ch.image.file) {
              await this.drafts.uploadDraftChapterImage(draftId, chapterId, ch.image.file);
            } else if (ch.image.mode === 'number' && ch.image.chapterNumber != null) {
              const blob = await this.images.fetchChapterNumberPng(ch.image.chapterNumber);
              await this.drafts.uploadDraftChapterImage(
                draftId,
                chapterId,
                new File([blob], `chapter-${ch.image.chapterNumber}.png`, {
                  type: blob.type || 'image/png',
                }),
              );
            }
          }
        }

        result = true;
      } catch {
        this.saveError.set('Failed to save chapters. Please try again.');
      } finally {
        this.saving.set(false);
      }
    });
    return result;
  }

  private async loadExistingDraft(): Promise<void> {
    // The Bulk Upload step feeds the shared model before this step is shown. If it already
    // holds chapters (pre-filled from the dropped audio files), do not overwrite them.
    if (this.model().chapters.length > 0) {
      this.loading.set(false);
      return;
    }
    try {
      const draft = await this.drafts.getCurrentDraft();
      if (!draft) return;

      const chapters = await Promise.all(
        draft.chapters.map(async (c) => {
          let titleAudio: TitleAudioSelection | null = null;
          if (c.titleText) {
            titleAudio = { mode: 'text', text: c.titleText, file: null };
          } else if (c.hasTitleAudio) {
            try {
              const blob = await this.drafts.downloadDraftChapterTitleAudio(draft.id, c.id);
              titleAudio = {
                mode: 'audio',
                text: '',
                file: new File([blob], 'title-audio.mp3', { type: blob.type || 'audio/mpeg' }),
              };
            } catch {
              /* binary missing — user can re-upload */
            }
          }

          let narrationFile: File | null = null;
          if (c.hasNarrationAudio) {
            try {
              const blob = await this.drafts.downloadDraftChapterNarration(draft.id, c.id);
              narrationFile = new File([blob], 'narration.mp3', { type: blob.type || 'audio/mpeg' });
            } catch {
              /* binary missing — user can re-upload */
            }
          }

          let image: NodeImageSelection | null = null;
          if (c.iconId) {
            image = { mode: 'icon', iconId: c.iconId, file: null };
          } else if (c.hasImage) {
            try {
              const blob = await this.drafts.downloadDraftChapterImage(draft.id, c.id);
              image = {
                mode: 'image',
                iconId: null,
                file: new File([blob], 'image.png', { type: blob.type || 'image/png' }),
              };
            } catch {
              /* binary missing — user can re-upload */
            }
          }

          return { id: c.id, name: c.name, titleAudio, narrationFile, image };
        }),
      );

      this.model.set({ chapters });
    } finally {
      this.loading.set(false);
    }
  }
}
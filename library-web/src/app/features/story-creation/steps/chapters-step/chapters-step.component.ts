import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField, required, submit } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PacksService } from '../../../../core/services/packs.service';
import type { NodeImageSelection } from '../../../../core/models';
import { AudioDropInputComponent } from '../../components/audio-drop-input/audio-drop-input.component';
import { NodeImageInputComponent } from '../../components/node-image-input/node-image-input.component';
import { TitleAudioInputComponent, TitleAudioSelection } from '../../components/title-audio-input/title-audio-input.component';

export interface ChapterFormModel {
  id: string;
  name: string;
  titleAudio: TitleAudioSelection | null;
  narrationFile: File | null;
  image: NodeImageSelection | null;
}

export interface ChaptersFormModel {
  chapters: ChapterFormModel[];
}

function emptyChapter(): ChapterFormModel {
  return { id: '', name: '', titleAudio: null, narrationFile: null, image: null };
}

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
})
export class ChaptersStepComponent {
  private readonly packs = inject(PacksService);

  readonly model = signal<ChaptersFormModel>({
    chapters: [],
  });

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

  constructor() {
    void this.loadExistingDraft();
  }

  addChapter(): void {
    this.model.update((m) => ({ chapters: [...m.chapters, emptyChapter()] }));
  }

  removeChapter(index: number): void {
    const chapter = this.model().chapters[index];
    if (chapter?.id && this.packs.draftId()) {
      void this.packs
        .deleteDraftChapter(this.packs.draftId() ?? '', chapter.id)
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
        const draftId = await this.packs.ensureDraft();
        const chapters = this.model().chapters;

        for (const ch of chapters) {
          let chapterId = ch.id;
          if (!chapterId) {
            chapterId = await this.packs.addDraftChapter(draftId, ch.name);
            this.model.update((m) => ({
              chapters: m.chapters.map((c) => (c === ch ? { ...c, id: chapterId } : c)),
            }));
          }

          if (ch.titleAudio) {
            if (ch.titleAudio.mode === 'text' && ch.titleAudio.text.trim()) {
              await this.packs.setDraftChapterTitleText(draftId, chapterId, ch.titleAudio.text.trim());
            } else if (ch.titleAudio.mode === 'audio' && ch.titleAudio.file) {
              await this.packs.uploadDraftChapterTitleAudio(draftId, chapterId, ch.titleAudio.file);
            }
          }

          if (ch.narrationFile) {
            await this.packs.uploadDraftChapterNarration(draftId, chapterId, ch.narrationFile);
          }

          if (ch.image) {
            if (ch.image.mode === 'icon' && ch.image.iconId) {
              await this.packs.setDraftChapterIcon(draftId, chapterId, ch.image.iconId);
            } else if (ch.image.mode === 'image' && ch.image.file) {
              await this.packs.uploadDraftChapterImage(draftId, chapterId, ch.image.file);
            } else if (ch.image.mode === 'number' && ch.image.chapterNumber != null) {
              const blob = await this.packs.fetchChapterNumberPng(ch.image.chapterNumber);
              await this.packs.uploadDraftChapterImage(
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
    try {
      const draft = await this.packs.getCurrentDraft();
      if (!draft) return;

      const chapters = await Promise.all(
        draft.chapters.map(async (c) => {
          let titleAudio: TitleAudioSelection | null = null;
          if (c.titleText) {
            titleAudio = { mode: 'text', text: c.titleText, file: null };
          } else if (c.hasTitleAudio) {
            try {
              const blob = await this.packs.downloadDraftChapterTitleAudio(draft.id, c.id);
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
              const blob = await this.packs.downloadDraftChapterNarration(draft.id, c.id);
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
              const blob = await this.packs.downloadDraftChapterImage(draft.id, c.id);
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
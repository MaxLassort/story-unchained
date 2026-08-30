import { Injectable, inject, signal, type WritableSignal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { StoryDraftService } from '../../core/services/story-draft.service';
import { StoryImageService } from '../../core/services/story-image.service';
import type { NodeImageSelection } from '../../core/models';
import type { TitleAudioSelection } from './components/title-audio-input/title-audio-input.component';
import type { ChaptersFormModel } from './chapter-templates';

/**
 * Shared, wizard-scoped state holding the chapters being configured. The Bulk Upload step
 * pre-fills chapters here (one per dropped narration file) and the Chapters step shows and
 * saves them. Provided once on the story-creation page so both steps read/write the same list.
 */
@Injectable()
export class ChaptersEditorState {
  private readonly drafts = inject(StoryDraftService);
  private readonly images = inject(StoryImageService);

  readonly model: WritableSignal<ChaptersFormModel> = signal({ chapters: [] });
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  private loadPromise: Promise<void> | null = null;

  constructor() {
    void this.loadExistingDraft();
  }

  async loadExistingDraft(): Promise<void> {
    if (this.loadPromise) return this.loadPromise;
    if (this.model().chapters.length > 0) return;

    this.loading.set(true);
    this.loadPromise = (async () => {
      try {
        const draft = await this.drafts.getCurrentDraft();
        if (!draft) return;
        if (this.model().chapters.length > 0) return;

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
        this.loadPromise = null;
      }
    })();

    return this.loadPromise;
  }

  async save(): Promise<boolean> {
    this.saveError.set(null);
    const chapters = this.model().chapters;
    if (chapters.length === 0) return true;

    this.saving.set(true);
    try {
      const draftId = await this.drafts.ensureDraft();

      for (const ch of chapters) {
        let chapterId = ch.id;
        if (!chapterId) {
          chapterId = await this.drafts.addDraftChapter(draftId, ch.name);
          this.model.update((m) => ({
            chapters: m.chapters.map((c) => (c === ch ? { ...c, id: chapterId } : c)),
          }));
        } else {
          await this.drafts.patchDraftNode(draftId, chapterId, { name: ch.name });
        }

        if (ch.titleAudio) {
          if (ch.titleAudio.mode === 'text' && ch.titleAudio.text.trim()) {
            await this.drafts.patchDraftNode(draftId, chapterId, { titleText: ch.titleAudio.text.trim() });
          } else if (ch.titleAudio.mode === 'audio' && ch.titleAudio.file) {
            await this.drafts.uploadDraftFile(
              draftId,
              { scope: 'chapter', chapterId, field: 'titleAudio' },
              ch.titleAudio.file,
            );
          }
        }

        if (ch.narrationFile) {
          await this.drafts.uploadDraftFile(
            draftId,
            { scope: 'chapter', chapterId, field: 'narration' },
            ch.narrationFile,
          );
        }

        if (ch.image) {
          if (ch.image.mode === 'icon' && ch.image.iconId) {
            await this.drafts.patchDraftNode(draftId, chapterId, { iconId: ch.image.iconId });
          } else if (ch.image.mode === 'image' && ch.image.file) {
            await this.drafts.uploadDraftFile(
              draftId,
              { scope: 'chapter', chapterId, field: 'image' },
              ch.image.file,
            );
          } else if (ch.image.mode === 'number' && ch.image.chapterNumber != null) {
            const blob = await this.images.fetchChapterNumberPng(ch.image.chapterNumber);
            await this.drafts.uploadDraftFile(
              draftId,
              { scope: 'chapter', chapterId, field: 'image' },
              new File([blob], `chapter-${ch.image.chapterNumber}.png`, {
                type: blob.type || 'image/png',
              }),
            );
          }
        }
      }

      return true;
    } catch (err) {
      this.saveError.set(
        err instanceof HttpErrorResponse
          ? (err.error?.error ??
              err.error?.message ??
              (err.status === 409
                ? 'API key missing for the selected TTS provider. Add it in Settings, or switch to the free provider (Google Translate).'
                : err.status === 502
                ? 'The selected TTS provider failed. Switch to the free provider (Google Translate) and try again.'
                : err.message ?? 'Failed to save chapters. Please try again.'))
          : 'Failed to save chapters. Please try again.',
      );
      return false;
    } finally {
      this.saving.set(false);
    }
  }

  deleteChapter(index: number): void {
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

  clearChapters(): void {
    const chapters = this.model().chapters;
    const draftId = this.drafts.draftId();
    if (draftId) {
      for (const ch of chapters) {
        if (ch.id) {
          void this.drafts.deleteDraftChapter(draftId, ch.id).catch(() => {});
        }
      }
    }
    this.model.set({ chapters: [] });
  }
}

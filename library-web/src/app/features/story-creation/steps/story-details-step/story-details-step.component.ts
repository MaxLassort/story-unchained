import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { form, FormField, required, submit } from '@angular/forms/signals';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { StoryDraftService } from '../../../../core/services/story-draft.service';
import { StoryImageService } from '../../../../core/services/story-image.service';
import type { NodeImageSelection } from '../../../../core/models';
import { ImageDropInputComponent } from '../../components/image-drop-input/image-drop-input.component';
import { NodeImageInputComponent } from '../../components/node-image-input/node-image-input.component';
import { TitleAudioInputComponent, TitleAudioSelection } from '../../components/title-audio-input/title-audio-input.component';

export interface StoryDetailsModel {
  title: string;
  description: string;
  titleAudio: TitleAudioSelection | null;
  menuAudio: TitleAudioSelection | null;
  thumbnail: File | null;
  cover: NodeImageSelection | null;
}

@Component({
  selector: 'app-story-details-step',
  imports: [FormField, ImageDropInputComponent, NodeImageInputComponent, MatFormFieldModule, MatInputModule, MatProgressSpinnerModule, TitleAudioInputComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './story-details-step.component.html',
  styleUrl: './story-details-step.component.scss',
})
export class StoryDetailsStepComponent {
  private readonly drafts = inject(StoryDraftService);
  private readonly images = inject(StoryImageService);

  readonly model = signal<StoryDetailsModel>({
    title: '',
    description: '',
    titleAudio: null,
    menuAudio: null,
    thumbnail: null,
    cover: null,
  });

  readonly detailsForm = form(this.model, (schemaPath) => {
    required(schemaPath.title, { message: 'Title is required' });
    required(schemaPath.thumbnail, { message: 'Thumbnail is required' });
    required(schemaPath.cover, { message: 'Cover image is required' });
  });

  readonly complete = computed(() => {
    if (!this.detailsForm().valid()) return false;
    const cover = this.detailsForm().value().cover;
    if (!cover) return false;
    if (cover.mode === 'icon') return cover.iconId !== null;
    if (cover.mode === 'number') return cover.chapterNumber != null;
    return cover.file !== null;
  });

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  constructor() {
    void this.loadExistingDraft();
  }

  async save(): Promise<boolean> {
    this.saveError.set(null);
    let result = false;
    await submit(this.detailsForm, async () => {
      this.saving.set(true);
      try {
        const { title, description, titleAudio, menuAudio, thumbnail, cover } = this.model();
        const draftId = await this.drafts.ensureDraft();

        await this.drafts.updateDraftMetadata(draftId, { title, description });

        if (thumbnail) {
          await this.drafts.uploadDraftThumbnail(draftId, thumbnail);
        }

        if (cover) {
          const coverFile = await this.coverToFile(cover);
          if (coverFile) await this.drafts.uploadDraftCover(draftId, coverFile);
        }

        if (titleAudio) {
          if (titleAudio.mode === 'text' && titleAudio.text.trim()) {
            await this.drafts.setDraftTitleText(draftId, titleAudio.text.trim());
          } else if (titleAudio.mode === 'audio' && titleAudio.file) {
            await this.drafts.uploadDraftTitleAudio(draftId, titleAudio.file);
          }
        }

        if (menuAudio) {
          if (menuAudio.mode === 'text' && menuAudio.text.trim()) {
            await this.drafts.setDraftMenuText(draftId, menuAudio.text.trim());
          } else if (menuAudio.mode === 'audio' && menuAudio.file) {
            await this.drafts.uploadDraftMenuAudio(draftId, menuAudio.file);
          }
        }

        result = true;
      } catch {
        this.saveError.set('Failed to save story details. Please try again.');
      } finally {
        this.saving.set(false);
      }
    });
    return result;
  }

  private async coverToFile(cover: NodeImageSelection): Promise<File | null> {
    if (cover.mode === 'image' && cover.file) return cover.file;
    if (cover.mode === 'icon' && cover.iconId) {
      const blob = await this.images.fetchIconPng(cover.iconId);
      return new File([blob], `${cover.iconId}.png`, { type: 'image/png' });
    }
    if (cover.mode === 'number' && cover.chapterNumber != null) {
      const blob = await this.images.fetchChapterNumberPng(cover.chapterNumber);
      return new File([blob], `chapter-${cover.chapterNumber}.png`, { type: 'image/png' });
    }
    return null;
  }

  private async loadExistingDraft(): Promise<void> {
    try {
      const draft = await this.drafts.getCurrentDraft();
      if (!draft) return;

      let thumbnail: File | null = null;
      if (draft.hasThumbnail) {
        try {
          const blob = await this.drafts.downloadDraftThumbnail(draft.id);
          thumbnail = new File([blob], 'thumbnail.png', { type: blob.type || 'image/png' });
        } catch {
          /* binary missing — user can re-upload */
        }
      }

      let cover: NodeImageSelection | null = null;
      if (draft.hasCover) {
        try {
          const blob = await this.drafts.downloadDraftCover(draft.id);
          cover = { mode: 'image', iconId: null, file: new File([blob], 'cover.png', { type: blob.type || 'image/png' }) };
        } catch {
          /* binary missing — user can re-upload */
        }
      }

      let titleAudio: TitleAudioSelection | null = null;
      if (draft.titleText) {
        titleAudio = { mode: 'text', text: draft.titleText, file: null };
      } else if (draft.hasTitleAudio) {
        try {
          const blob = await this.drafts.downloadDraftTitleAudio(draft.id);
          const file = new File([blob], 'title-audio.mp3', { type: blob.type || 'audio/mpeg' });
          titleAudio = { mode: 'audio', text: '', file };
        } catch {
          /* binary missing */
        }
      }

      let menuAudio: TitleAudioSelection | null = null;
      if (draft.menuText) {
        menuAudio = { mode: 'text', text: draft.menuText, file: null };
      } else if (draft.hasMenuAudio) {
        try {
          const blob = await this.drafts.downloadDraftMenuAudio(draft.id);
          const file = new File([blob], 'menu-audio.mp3', { type: blob.type || 'audio/mpeg' });
          menuAudio = { mode: 'audio', text: '', file };
        } catch {
          /* binary missing */
        }
      }

      this.model.set({
        title: draft.title ?? '',
        description: draft.description ?? '',
        titleAudio,
        menuAudio,
        thumbnail,
        cover,
      });
    } finally {
      this.loading.set(false);
    }
  }
}

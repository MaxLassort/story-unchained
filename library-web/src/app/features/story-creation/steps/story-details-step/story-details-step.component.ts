import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { PacksService } from '../../../../core/services/packs.service';
import type { NodeImageSelection } from '../../../../core/models';
import { ImageDropInputComponent } from '../../components/image-drop-input/image-drop-input.component';
import { NodeImageInputComponent } from '../../components/node-image-input/node-image-input.component';
import { TitleAudioInputComponent, TitleAudioSelection } from '../../components/title-audio-input/title-audio-input.component';

export interface StoryDetailsModel {
  title: string;
  description: string;
  titleAudio: TitleAudioSelection | null;
  thumbnail: File | null;
  cover: NodeImageSelection | null;
}

@Component({
  selector: 'app-story-details-step',
  imports: [FormField, ImageDropInputComponent, NodeImageInputComponent, MatFormFieldModule, MatInputModule, TitleAudioInputComponent],
  templateUrl: './story-details-step.component.html',
  styleUrl: './story-details-step.component.scss',
})
export class StoryDetailsStepComponent {
  private readonly packs = inject(PacksService);

  readonly model = signal<StoryDetailsModel>({
    title: '',
    description: '',
    titleAudio: null,
    thumbnail: null,
    cover: null,
  });

  readonly detailsForm = form(this.model, (schemaPath) => {
    required(schemaPath.title, { message: 'Title is required' });
    required(schemaPath.thumbnail, { message: 'Thumbnail is required' });
    required(schemaPath.cover, { message: 'Cover image is required' });
  });

  /** Whether the step is complete (drives the linear stepper). */
  readonly complete = computed(() => {
    if (!this.detailsForm().valid()) return false;
    const cover = this.detailsForm().value().cover;
    if (!cover) return false;
    return cover.mode === 'icon' ? cover.iconId !== null : cover.file !== null;
  });

  readonly thumbnailUploading = signal(false);
  readonly thumbnailError = signal<string | null>(null);
  readonly coverUploading = signal(false);
  readonly coverError = signal<string | null>(null);

  private lastUploadedThumbnail: File | null = null;
  private lastUploadedCoverKey: string | File | null = null;

  constructor() {
    effect(() => {
      const file = this.detailsForm().value().thumbnail;
      untracked(() => {
        if (file === this.lastUploadedThumbnail) return;
        this.lastUploadedThumbnail = file;
        void this.uploadThumbnail(file);
      });
    });

    effect(() => {
      const cover = this.detailsForm().value().cover;
      untracked(() => {
        const key = this.coverKey(cover);
        if (key === this.lastUploadedCoverKey) return;
        this.lastUploadedCoverKey = key;
        void this.uploadCover(cover);
      });
    });
  }

  private async uploadThumbnail(file: File | null): Promise<void> {
    this.thumbnailUploading.set(true);
    this.thumbnailError.set(null);
    if (!file) {
      this.thumbnailUploading.set(false);
      return;
    }
    try {
      const draftId = await this.packs.ensureDraft();
      await this.packs.uploadDraftThumbnail(draftId, file);
    } catch {
      this.thumbnailError.set('Thumbnail upload failed. Please try again.');
    } finally {
      this.thumbnailUploading.set(false);
    }
  }

  private async uploadCover(cover: NodeImageSelection | null): Promise<void> {
    this.coverUploading.set(true);
    this.coverError.set(null);
    if (!cover) {
      this.coverUploading.set(false);
      return;
    }
    try {
      const draftId = await this.packs.ensureDraft();
      let file: File;
      if (cover.mode === 'icon' && cover.iconId) {
        const blob = await this.packs.fetchIconPng(cover.iconId);
        file = new File([blob], `${cover.iconId}.png`, { type: 'image/png' });
      } else if (cover.mode === 'image' && cover.file) {
        file = cover.file;
      } else {
        this.coverUploading.set(false);
        return;
      }
      await this.packs.uploadDraftCover(draftId, file);
    } catch {
      this.coverError.set('Cover upload failed. Please try again.');
    } finally {
      this.coverUploading.set(false);
    }
  }

  private coverKey(sel: NodeImageSelection | null): string | File | null {
    if (!sel) return null;
    return sel.mode === 'icon' ? sel.iconId : sel.file;
  }
}

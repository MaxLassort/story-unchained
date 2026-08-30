import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type {
  DraftCreatedResponse,
  StoryDraftSummary,
  UpdateDraftRequest,
} from '../models';
import { silentHttpContext } from './http-context';
import { environment } from '../../../environments/environment';

/**
 * Story draft lifecycle: a single in-progress story draft (chapters, audio or
 * TTS text, images) held on disk in the temp folder until finalization. Owns the
 * shared `draftId` signal consumed by the story-creation stepper.
 *
 * All requests use {@link silentHttpContext}: errors are handled inline by the
 * story-creation flow (saveError/finalizeError) or silently tolerated (missing
 * draft binaries on reload), so the global error snackbar is suppressed to avoid
 * a redundant second notification.
 */
@Injectable({ providedIn: 'root' })
export class StoryDraftService {
  private readonly http = inject(HttpClient);
  private readonly draftsUrl = `${environment.apiUrl}/stories/drafts`;

  readonly draftId = signal<string | null>(null);
  private draftPromise: Promise<string> | null = null;

  async ensureDraft(): Promise<string> {
    const existing = this.draftId();
    if (existing) return existing;
    if (this.draftPromise) return this.draftPromise;
    this.draftPromise = firstValueFrom(
      this.http.post<DraftCreatedResponse>(this.draftsUrl, {}, { context: silentHttpContext() }),
    )
      .then((res) => {
        this.draftId.set(res.draftId);
        return res.draftId;
      })
      .finally(() => {
        this.draftPromise = null;
      });
    return this.draftPromise;
  }

  async getCurrentDraft(): Promise<StoryDraftSummary | null> {
    try {
      const draft = await firstValueFrom(
        this.http.get<StoryDraftSummary>(`${this.draftsUrl}/current`, { context: silentHttpContext() }),
      );
      this.draftId.set(draft.id);
      return draft;
    } catch {
      return null;
    }
  }

  async finalizeDraft(id: string): Promise<{ packId: string }> {
    return firstValueFrom(
      this.http.post<{ packId: string }>(`${this.draftsUrl}/${id}/finalize`, {}, { context: silentHttpContext() }),
    );
  }

  async getDraft(id: string): Promise<StoryDraftSummary> {
    return firstValueFrom(
      this.http.get<StoryDraftSummary>(`${this.draftsUrl}/${id}`, { context: silentHttpContext() }),
    );
  }

  async updateDraftMetadata(id: string, request: UpdateDraftRequest): Promise<StoryDraftSummary> {
    return firstValueFrom(
      this.http.patch<StoryDraftSummary>(`${this.draftsUrl}/${id}`, request, { context: silentHttpContext() }),
    );
  }

  async downloadDraftThumbnail(id: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/thumbnail/file`, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async downloadDraftCover(id: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/cover/file`, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async downloadDraftTitleAudio(id: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/title-audio/file`, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async downloadDraftMenuAudio(id: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/menu-audio/file`, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async uploadDraftTitleAudio(id: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/title-audio`, form, { context: silentHttpContext() }),
    );
  }

  async setDraftTitleText(id: string, text: string): Promise<StoryDraftSummary> {
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/title-text`, { text }, { context: silentHttpContext() }),
    );
  }

  async uploadDraftMenuAudio(id: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/menu-audio`, form, { context: silentHttpContext() }),
    );
  }

  async setDraftMenuText(id: string, text: string): Promise<StoryDraftSummary> {
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/menu-text`, { text }, { context: silentHttpContext() }),
    );
  }

  async uploadDraftThumbnail(id: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/thumbnail`, form, { context: silentHttpContext() }),
    );
  }

  async uploadDraftCover(id: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/cover`, form, { context: silentHttpContext() }),
    );
  }

  async addDraftChapter(id: string, name: string): Promise<string> {
    const res = await firstValueFrom(
      this.http.post<{ draftId: string; chapterId: string }>(
        `${this.draftsUrl}/${id}/chapters`,
        { name },
        { context: silentHttpContext() },
      ),
    );
    return res.chapterId;
  }

  async deleteDraftChapter(id: string, chapterId: string): Promise<void> {
    await firstValueFrom(
      this.http.delete(`${this.draftsUrl}/${id}/chapters/${chapterId}`, { context: silentHttpContext() }),
    );
  }

  async uploadDraftChapterTitleAudio(id: string, chapterId: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(
        `${this.draftsUrl}/${id}/chapters/${chapterId}/audio`,
        form,
        { context: silentHttpContext() },
      ),
    );
  }

  async setDraftChapterTitleText(id: string, chapterId: string, text: string): Promise<StoryDraftSummary> {
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(
        `${this.draftsUrl}/${id}/chapters/${chapterId}/title-text`,
        { text },
        { context: silentHttpContext() },
      ),
    );
  }

  async uploadDraftChapterNarration(id: string, chapterId: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(
        `${this.draftsUrl}/${id}/chapters/${chapterId}/narration`,
        form,
        { context: silentHttpContext() },
      ),
    );
  }

  async uploadDraftChapterImage(id: string, chapterId: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(
        `${this.draftsUrl}/${id}/chapters/${chapterId}/image`,
        form,
        { context: silentHttpContext() },
      ),
    );
  }

  async setDraftChapterIcon(id: string, chapterId: string, iconId: string): Promise<StoryDraftSummary> {
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(
        `${this.draftsUrl}/${id}/chapters/${chapterId}/icon`,
        { iconId },
        { context: silentHttpContext() },
      ),
    );
  }

  async downloadDraftChapterTitleAudio(id: string, chapterId: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/chapters/${chapterId}/title-audio/file`, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async downloadDraftChapterNarration(id: string, chapterId: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/chapters/${chapterId}/narration/file`, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async downloadDraftChapterImage(id: string, chapterId: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/chapters/${chapterId}/image/file`, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }
}

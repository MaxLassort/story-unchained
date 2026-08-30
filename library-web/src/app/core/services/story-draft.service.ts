import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type {
  DraftCreatedResponse,
  DraftFileTarget,
  PatchNodePayload,
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

  // ------------------------------------------------------------------
  // Consolidated endpoints (preferred): one PUT for binaries, one PATCH
  // for node edits. The per-field methods above are kept only until the
  // migration of all steps is verified, then removed.
  // ------------------------------------------------------------------

  /** Consolidated binary upload: one PUT for every draft file (pack or chapter). */
  async uploadDraftFile(id: string, target: DraftFileTarget, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    const params: Record<string, string> = { scope: target.scope, field: target.field };
    if (target.chapterId) params['chapterId'] = target.chapterId;
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/files`, form, {
        params,
        context: silentHttpContext(),
      }),
    );
  }

  /**
   * Consolidated node patch: edits the pack root (nodeId = draft id) or a chapter
   * (nodeId = chapter id). Text fields are TTS-synthesized immediately by the backend
   * and stored as audio files in the draft. Throws on 409 (API key missing).
   */
  async patchDraftNode(id: string, nodeId: string, patch: PatchNodePayload): Promise<StoryDraftSummary> {
    return firstValueFrom(
      this.http.patch<StoryDraftSummary>(`${this.draftsUrl}/${id}/nodes/${nodeId}`, patch, {
        context: silentHttpContext(),
      }),
    );
  }
}

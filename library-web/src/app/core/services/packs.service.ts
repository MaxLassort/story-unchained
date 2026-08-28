import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { Pack, PagedPacksResponse, SyncJobStartResponse, SyncJobStatusResponse, UpdatePackMetadataRequest, PackConversionRequest, PackConversionResponse, DraftCreatedResponse, StoryDraftSummary, UpdateDraftRequest } from '../models';
import { ApiStatusResponse } from '../models';

import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class PacksService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/packs`;
  private readonly draftsUrl = `${environment.apiUrl}/stories/drafts`;

  readonly draftId = signal<string | null>(null);
  private draftPromise: Promise<string> | null = null;

  readonly page = signal(0);
  readonly pageSize = signal(24);
  readonly searchTerm = signal('');
  readonly showOfficial = signal(true);
  readonly showFrFr = signal(true);
  readonly showUnavailable = signal(false);

  private readonly resource = httpResource<PagedPacksResponse>(() => {
    const params: Record<string, string> = {
      page: String(this.page()),
      size: String(this.pageSize()),
    };
    const s = this.searchTerm();
    if (s) params['search'] = s;
    if (!this.showOfficial()) params['official'] = 'false';
    if (this.showFrFr()) params['locale'] = 'fr_FR';
    if (!this.showUnavailable()) params['inLibrary'] = 'true';
    return { url: this.baseUrl, params };
  });

  readonly packs = computed(() => this.resource.value()?.content ?? []);
  readonly total = computed(() => this.resource.value()?.totalCount ?? 0);
  readonly totalPages = computed(() => Math.max(1, Math.ceil(this.total() / this.pageSize())));
  readonly loading = this.resource.isLoading;

  async getAllPacks(): Promise<Pack[]> {
    return firstValueFrom(this.http.get<Pack[]>(`${this.baseUrl}/all`));
  }

  async sync(): Promise<SyncJobStartResponse> {
    return firstValueFrom(this.http.post<SyncJobStartResponse>(`${this.baseUrl}/sync`, {}));
  }

  async getSyncStatus(jobId: number): Promise<SyncJobStatusResponse> {
    return firstValueFrom(this.http.get<SyncJobStatusResponse>(`${this.baseUrl}/sync/${jobId}`));
  }

  async deletePack(id: string): Promise<ApiStatusResponse> {
    const res = await firstValueFrom(this.http.delete<ApiStatusResponse>(`${this.baseUrl}/${id}`));
    this.refresh();
    return res;
  }

  async updateMetadata(id: string, request: UpdatePackMetadataRequest): Promise<Pack> {
    const res = await firstValueFrom(this.http.patch<Pack>(`${this.baseUrl}/${id}/metadata`, request));
    this.refresh();
    return res;
  }

  async convert(id: string, request: PackConversionRequest): Promise<PackConversionResponse> {
    return firstValueFrom(this.http.post<PackConversionResponse>(`${this.baseUrl}/${id}/convert`, request));
  }

  async uploadThumbnail(id: string, image: Blob): Promise<void> {
    await firstValueFrom(this.http.patch(`${this.baseUrl}/${id}/thumbnail`, image));
    this.refresh();
  }

  async ensureDraft(): Promise<string> {
    const existing = this.draftId();
    if (existing) return existing;
    if (this.draftPromise) return this.draftPromise;
    this.draftPromise = firstValueFrom(this.http.post<DraftCreatedResponse>(this.draftsUrl, {}))
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
        this.http.get<StoryDraftSummary>(`${this.draftsUrl}/current`),
      );
      this.draftId.set(draft.id);
      return draft;
    } catch {
      return null;
    }
  }

  async finalizeDraft(id: string): Promise<{ packId: string }> {
    return firstValueFrom(
      this.http.post<{ packId: string }>(`${this.draftsUrl}/${id}/finalize`, {}),
    );
  }

  async downloadDraftThumbnail(id: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/thumbnail/file`, { responseType: 'blob' }),
    );
  }

  async downloadDraftCover(id: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/cover/file`, { responseType: 'blob' }),
    );
  }

  async downloadDraftTitleAudio(id: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/title-audio/file`, { responseType: 'blob' }),
    );
  }

  async getDraft(id: string): Promise<StoryDraftSummary> {
    return firstValueFrom(this.http.get<StoryDraftSummary>(`${this.draftsUrl}/${id}`));
  }

  async updateDraftMetadata(id: string, request: UpdateDraftRequest): Promise<StoryDraftSummary> {
    return firstValueFrom(this.http.patch<StoryDraftSummary>(`${this.draftsUrl}/${id}`, request));
  }

  async uploadDraftTitleAudio(id: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/title-audio`, form));
  }

  async setDraftTitleText(id: string, text: string): Promise<StoryDraftSummary> {
    return firstValueFrom(this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/title-text`, { text }));
  }

  async uploadDraftThumbnail(id: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/thumbnail`, form));
  }

  async uploadDraftCover(id: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/cover`, form));
  }

  async addDraftChapter(id: string, name: string): Promise<string> {
    const res = await firstValueFrom(
      this.http.post<{ draftId: string; chapterId: string }>(`${this.draftsUrl}/${id}/chapters`, { name }),
    );
    return res.chapterId;
  }

  async deleteDraftChapter(id: string, chapterId: string): Promise<void> {
    await firstValueFrom(this.http.delete(`${this.draftsUrl}/${id}/chapters/${chapterId}`));
  }

  async uploadDraftChapterTitleAudio(id: string, chapterId: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/chapters/${chapterId}/audio`, form),
    );
  }

  async setDraftChapterTitleText(id: string, chapterId: string, text: string): Promise<StoryDraftSummary> {
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/chapters/${chapterId}/title-text`, { text }),
    );
  }

  async uploadDraftChapterNarration(id: string, chapterId: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/chapters/${chapterId}/narration`, form),
    );
  }

  async uploadDraftChapterImage(id: string, chapterId: string, file: File): Promise<StoryDraftSummary> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/chapters/${chapterId}/image`, form),
    );
  }

  async setDraftChapterIcon(id: string, chapterId: string, iconId: string): Promise<StoryDraftSummary> {
    return firstValueFrom(
      this.http.put<StoryDraftSummary>(`${this.draftsUrl}/${id}/chapters/${chapterId}/icon`, { iconId }),
    );
  }

  async downloadDraftChapterTitleAudio(id: string, chapterId: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/chapters/${chapterId}/title-audio/file`, { responseType: 'blob' }),
    );
  }

  async downloadDraftChapterNarration(id: string, chapterId: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/chapters/${chapterId}/narration/file`, { responseType: 'blob' }),
    );
  }

  async downloadDraftChapterImage(id: string, chapterId: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.draftsUrl}/${id}/chapters/${chapterId}/image/file`, { responseType: 'blob' }),
    );
  }

  iconPreviewUrl(iconId: string): string {
    return `${environment.apiUrl}/stories/images/preview?iconId=${encodeURIComponent(iconId)}`;
  }

  chapterNumberPreviewUrl(chapterNumber: number): string {
    return `${environment.apiUrl}/stories/images/preview?chapterNumber=${chapterNumber}`;
  }

  async fetchIconPng(iconId: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${environment.apiUrl}/stories/images/preview`, {
        params: { iconId },
        responseType: 'blob',
      }),
    );
  }

  async renderSvg(svg: File): Promise<Blob> {
    const form = new FormData();
    form.append('file', svg);
    return firstValueFrom(
      this.http.post(`${environment.apiUrl}/stories/images/render`, form, { responseType: 'blob' }),
    );
  }

  refresh(): void {
    this.resource.reload();
  }
}

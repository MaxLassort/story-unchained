import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type {
  Pack,
  PagedPacksResponse,
  SyncStatusEvent,
  UpdatePackMetadataRequest,
  PackConversionRequest,
  PackConversionResponse,
} from '../models';
import { ApiStatusResponse } from '../models';
import { LanguageService } from './language.service';

import { environment } from '../../../environments/environment';

const LOCALE_MAP: Record<'fr' | 'en', string> = {
  fr: 'fr_FR',
  en: 'en_US',
};

/**
 * Pack library: paginated listing with reactive filters, synchronization,
 * metadata, format conversion and thumbnails. Story drafts and chapter image
 * generation live in {@link StoryDraftService} and {@link StoryImageService}.
 */
@Injectable({ providedIn: 'root' })
export class PacksService {
  private readonly http = inject(HttpClient);
  private readonly lang = inject(LanguageService);
  private readonly baseUrl = `${environment.apiUrl}/packs`;

  readonly page = signal(0);
  readonly pageSize = signal(24);
  readonly searchTerm = signal('');
  readonly showOfficial = signal(true);
  readonly showUnchainedOnly = signal(false);
  readonly showCurrentLocale = signal(true);
  readonly ageMin = signal<number | null>(null);
  readonly ageMax = signal<number | null>(null);
  readonly officialMode = signal(false);

  private readonly resource = httpResource<PagedPacksResponse>(() => {
    const official = this.officialMode();
    const params: Record<string, string> = {
      page: String(this.page()),
      size: String(this.pageSize()),
    };
    const s = this.searchTerm();
    if (s) params['search'] = s;
    if (!official && !this.showOfficial()) params['official'] = 'false';
    if (!official && this.showUnchainedOnly()) params['unchained'] = 'true';
    if (this.showCurrentLocale()) {
      params['locale'] = LOCALE_MAP[this.lang.currentLang()];
    }
    if (!official) params['inLibrary'] = 'true';
    const min = this.ageMin();
    if (min !== null) params['ageMin'] = String(min);
    const max = this.ageMax();
    if (max !== null) params['ageMax'] = String(max);
    return {
      url: official ? `${environment.apiUrl}/metadata/official` : this.baseUrl,
      params,
    };
  });

  readonly packs = computed(() => this.resource.value()?.content ?? []);
  readonly total = computed(() => this.resource.value()?.totalCount ?? 0);
  readonly totalPages = computed(() => Math.max(1, Math.ceil(this.total() / this.pageSize())));
  readonly loading = this.resource.isLoading;

  async getAllPacks(): Promise<Pack[]> {
    return firstValueFrom(this.http.get<Pack[]>(`${this.baseUrl}/all`));
  }

  async sync(): Promise<void> {
    await firstValueFrom(this.http.post<void>(`${this.baseUrl}/sync`, {}));
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

  refresh(): void {
    this.resource.reload();
  }
}

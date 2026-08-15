import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { ApiStatusResponse } from '../models';

import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class MetadataService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/metadata`;

  readonly refreshing = signal(false);

  async refresh(): Promise<ApiStatusResponse> {
    this.refreshing.set(true);
    try {
      return await firstValueFrom(this.http.post<ApiStatusResponse>(`${this.baseUrl}/refresh`, {}));
    } finally {
      this.refreshing.set(false);
    }
  }

  async refreshUnofficial(): Promise<ApiStatusResponse> {
    this.refreshing.set(true);
    try {
      return await firstValueFrom(this.http.post<ApiStatusResponse>(`${this.baseUrl}/refresh-unofficial`, {}));
    } finally {
      this.refreshing.set(false);
    }
  }
}

import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { Settings } from '../models';
import { SKIP_ERROR_SNACKBAR } from './http-context';

import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/settings`;
  private readonly silentContext = new HttpContext().set(SKIP_ERROR_SNACKBAR, true);

  readonly settings = signal<Settings>({ libraryPath: '', targetDeviceType: null });

  async load(): Promise<void> {
    try {
      const s = await firstValueFrom(
        this.http.get<Settings>(this.baseUrl, { context: this.silentContext }),
      );
      this.settings.set(s);
    } catch {
      // keep defaults
    }
  }

  async save(settings: Settings): Promise<Settings> {
    const s = await firstValueFrom(this.http.put<Settings>(this.baseUrl, settings));
    this.settings.set(s);
    return s;
  }
}

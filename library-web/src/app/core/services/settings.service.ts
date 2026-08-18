import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { Settings, TtsVoicesResponse } from '../models';
import { SKIP_ERROR_SNACKBAR } from './http-context';

import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/settings`;
  private readonly ttsBaseUrl = `${environment.apiUrl}/tts`;
  private readonly silentContext = new HttpContext().set(SKIP_ERROR_SNACKBAR, true);

  readonly settings = signal<Settings>({
    libraryPath: '',
    unofficialDbPath: null,
    targetDeviceType: null,
    ttsProvider: null,
    ttsOpenAiApiKey: null,
    ttsElevenLabsApiKey: null,
    ttsVoice: null,
    ttsLang: null,
  });

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

  /** Lists the voices available for a TTS provider (null/undefined → FREE). */
  async getVoices(provider: string | null): Promise<TtsVoicesResponse> {
    const params = provider ? `?provider=${encodeURIComponent(provider)}` : '';
    return firstValueFrom(
      this.http.get<TtsVoicesResponse>(`${this.ttsBaseUrl}/voices${params}`, {
        context: this.silentContext,
      }),
    );
  }
}

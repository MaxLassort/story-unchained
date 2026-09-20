import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { silentHttpContext } from './http-context';
import { environment } from '../../../environments/environment';

/**
 * Chapter image generation: Lucide icon previews, chapter-number rendering and
 * SVG → PNG conversion for the story-creation flow.
 *
 * All requests use {@link silentHttpContext}: errors are handled inline by the
 * story-creation flow, so the global error snackbar is suppressed to avoid a
 * redundant second notification.
 */
@Injectable({ providedIn: 'root' })
export class StoryImageService {
  private readonly http = inject(HttpClient);
  private readonly imagesUrl = `${environment.apiUrl}/stories/images`;

  iconPreviewUrl(iconId: string, strokeMultiplier = 1.0): string {
    return `${this.imagesUrl}/preview?iconId=${encodeURIComponent(iconId)}&strokeMultiplier=${strokeMultiplier}`;
  }

  chapterNumberPreviewUrl(chapterNumber: number, strokeMultiplier = 1.0): string {
    return `${this.imagesUrl}/preview?chapterNumber=${chapterNumber}&strokeMultiplier=${strokeMultiplier}`;
  }

  async fetchIconPng(iconId: string, strokeMultiplier = 1.0): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.imagesUrl}/preview`, {
        params: { iconId, strokeMultiplier: strokeMultiplier.toString() },
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async fetchChapterNumberPng(chapterNumber: number, strokeMultiplier = 1.0): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.imagesUrl}/preview`, {
        params: { chapterNumber: chapterNumber.toString(), strokeMultiplier: strokeMultiplier.toString() },
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async renderSvg(svg: File, strokeMultiplier = 1.0): Promise<Blob> {
    const form = new FormData();
    form.append('file', svg);
    return firstValueFrom(
      this.http.post(`${this.imagesUrl}/render?strokeMultiplier=${strokeMultiplier}`, form, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  /**
   * Local (non-AI) stylization: any PNG/JPEG/BMP → 320×240 high-contrast
   * grayscale PNG suitable for the Lunii screen preview and pack assets.
   */
  async prepareDeviceImage(file: File): Promise<Blob> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(
      this.http.post(`${this.imagesUrl}/prepare-device`, form, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }
}

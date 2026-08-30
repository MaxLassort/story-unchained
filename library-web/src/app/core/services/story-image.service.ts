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

  iconPreviewUrl(iconId: string): string {
    return `${this.imagesUrl}/preview?iconId=${encodeURIComponent(iconId)}`;
  }

  chapterNumberPreviewUrl(chapterNumber: number): string {
    return `${this.imagesUrl}/preview?chapterNumber=${chapterNumber}`;
  }

  async fetchIconPng(iconId: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.imagesUrl}/preview`, {
        params: { iconId },
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async fetchChapterNumberPng(chapterNumber: number): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${this.imagesUrl}/preview`, {
        params: { chapterNumber },
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }

  async renderSvg(svg: File): Promise<Blob> {
    const form = new FormData();
    form.append('file', svg);
    return firstValueFrom(
      this.http.post(`${this.imagesUrl}/render`, form, {
        responseType: 'blob',
        context: silentHttpContext(),
      }),
    );
  }
}

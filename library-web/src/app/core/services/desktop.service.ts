import { Injectable } from '@angular/core';

export interface OpenPathOptions {
  title?: string;
  defaultPath?: string;
  buttonLabel?: string;
  properties?: Array<'openFile' | 'openDirectory' | 'multiSelections' | 'createDirectory'>;
  filters?: Array<{ name: string; extensions: string[] }>;
}

export interface DesktopApi {
  apiUrl: string;
  versions: { electron: string; chrome: string; node: string };
  selectPath(options?: OpenPathOptions): Promise<string | null>;
}

declare global {
  interface Window {
    studioDesktop?: DesktopApi;
  }
}

@Injectable({ providedIn: 'root' })
export class DesktopService {
  get available(): boolean {
    return typeof window.studioDesktop?.selectPath === 'function';
  }

  async selectDirectory(options?: OpenPathOptions): Promise<string | null> {
    if (!this.available) return null;
    return window.studioDesktop!.selectPath({
      ...options,
      properties: ['openDirectory', 'createDirectory'],
    });
  }

  async selectFile(options?: OpenPathOptions): Promise<string | null> {
    if (!this.available) return null;
    return window.studioDesktop!.selectPath({
      ...options,
      properties: ['openFile'],
    });
  }
}

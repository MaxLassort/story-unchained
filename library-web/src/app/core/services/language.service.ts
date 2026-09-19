import { Injectable, signal, computed } from '@angular/core';

const STORAGE_KEY = 'storyunchained-lang';

@Injectable({ providedIn: 'root' })
export class LanguageService {
  readonly currentLang = signal<'fr' | 'en'>(this.loadLang());
  readonly isEnglish = computed(() => this.currentLang() === 'en');

  setLang(lang: 'fr' | 'en'): void {
    this.currentLang.set(lang);
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, lang);
    } catch {
      /* storage unavailable (SSR / locked-down test runners) */
    }
  }

  private loadLang(): 'fr' | 'en' {
    try {
      const stored = globalThis.localStorage?.getItem(STORAGE_KEY);
      return stored === 'en' ? 'en' : 'fr';
    } catch {
      return 'fr';
    }
  }
}

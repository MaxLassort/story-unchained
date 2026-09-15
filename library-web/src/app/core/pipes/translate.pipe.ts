import { Pipe, PipeTransform, inject } from '@angular/core';
import { LanguageService } from '../services/language.service';
import translations from '../../../locale/translations.fr.json';

export function translate(key: string, lang: 'fr' | 'en'): string {
  if (lang === 'en') {
    return key;
  }
  return translations[key as keyof typeof translations] ?? key;
}

@Pipe({ name: 'translate', pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly lang = inject(LanguageService);

  transform(key: string): string {
    return translate(key, this.lang.currentLang());
  }
}

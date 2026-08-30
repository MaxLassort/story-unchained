import type { NodeImageSelection } from '../../core/models';
import type { TitleAudioSelection } from './components/title-audio-input/title-audio-input.component';

/** A single chapter being configured (added manually or pre-filled by the bulk step). */
export interface ChapterFormModel {
  id: string;
  name: string;
  titleAudio: TitleAudioSelection | null;
  narrationFile: File | null;
  image: NodeImageSelection | null;
}

export interface ChaptersFormModel {
  chapters: ChapterFormModel[];
}

/**
 * Pre-rendered TTS title audio stored in the app's static assets
 * (`public/assets/title-tts/chapter-N.mp3`), one per chapter 1..[MAX_PRESET_TTS_CHAPTERS]
 * ("Chapitre un", "Chapitre deux", …). A freshly added chapter pre-selects the matching audio
 * file (mode 'audio', uploaded in place of a TTS text) so no extra synthesis is needed.
 */
export const MAX_PRESET_TTS_CHAPTERS = 15;

/** Relative asset URL of the pre-rendered TTS title audio for chapter N (1-based). */
export function chapterTitleAudioAssetUrl(n: number): string {
  return `assets/title-tts/chapter-${n}.mp3`;
}

/** Title of the chapter N (1-based) — "Chapitre N", matching the numbered chapter UI. */
export function chapterTitleForChapter(n: number): string {
  return `Chapitre ${n}`;
}

/**
 * Builds a chapter already pre-filled: name, title audio (pre-selected uploaded asset) and image
 * (chapter-number render) are set; only the chapter narration is left empty for the user (or the
 * bulk-audio step) to fill in. The title audio always comes from the uploaded asset file (mode
 * 'audio') — no TTS fallback, so finalize never re-synthesises a chapter title. When no
 * [titleAudioFile] is available (e.g. beyond the pre-rendered pool) the title audio stays empty.
 */
export function prefilledChapter(n: number, titleAudioFile?: File | null): ChapterFormModel {
  return {
    id: '',
    name: chapterTitleForChapter(n),
    titleAudio: titleAudioFile ? { mode: 'audio', text: '', file: titleAudioFile } : null,
    narrationFile: null,
    image: { mode: 'number', iconId: null, file: null, chapterNumber: n },
  };
}

/**
 * Loads the pre-rendered TTS title audio assets into in-memory [File]s, keyed by chapter number.
 * Missing assets are silently skipped (the matching chapter is left without a pre-selected title
 * audio for the user to fill in).
 */
export async function loadChapterTitleAudioPool(baseUri = document.baseURI): Promise<Map<number, File>> {
  const map = new Map<number, File>();
  for (let i = 1; i <= MAX_PRESET_TTS_CHAPTERS; i++) {
    try {
      const url = new URL(chapterTitleAudioAssetUrl(i), baseUri).href;
      const res = await fetch(url);
      if (!res.ok) continue;
      const blob = await res.blob();
      map.set(i, new File([blob], `chapter-${i}.mp3`, { type: blob.type || 'audio/mpeg' }));
    } catch {
      /* asset missing/unreachable — chapter stays without pre-selected title audio */
    }
  }
  return map;
}

import { Injectable, signal, type WritableSignal } from '@angular/core';
import type { ChaptersFormModel } from './chapter-templates';

/**
 * Shared, wizard-scoped state holding the chapters being configured. The Bulk Upload step
 * pre-fills chapters here (one per dropped narration file) and the Chapters step shows and
 * saves them. Provided once on the story-creation page so both steps read/write the same list.
 */
@Injectable()
export class ChaptersEditorState {
  readonly model: WritableSignal<ChaptersFormModel> = signal({ chapters: [] });
}

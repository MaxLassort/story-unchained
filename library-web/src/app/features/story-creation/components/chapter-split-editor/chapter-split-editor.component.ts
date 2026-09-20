import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import WaveSurfer from 'wavesurfer.js';
import RegionsPlugin, { type Region } from 'wavesurfer.js/plugins/regions';
import { TranslatePipe } from '../../../../core/pipes/translate.pipe';
import {
  buildSectionBounds,
  MIN_SECTION_GAP_S,
  roundToCentisecond,
  sliceAudioFile,
  type SectionBound,
} from '../title-audio-input/audio-slice.util';
import {
  deltaSecondsForUnit,
  formatTimecode,
  parseTimecode,
  selectionRangeForUnit,
  timecodeUnitFromInput,
  type TimecodeUnit,
} from '../title-audio-input/timecode.util';

/** Seconds of audio played when previewing a section edge. */
const PREVIEW_WINDOW_S = 2;

/**
 * Waveform editor for splitting a single narration file into chapters.
 * Separators are draggable markers (centisecond precision); each resulting
 * section can preview its start or end. Confirming emits sliced mono WAV files.
 */
@Component({
  selector: 'app-chapter-split-editor',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    TranslatePipe,
  ],
  templateUrl: './chapter-split-editor.component.html',
  styleUrl: './chapter-split-editor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChapterSplitEditorComponent {
  /** Source audio to split (exactly one file). */
  readonly file = input.required<File>();

  readonly cancelled = output<void>();
  /** Sliced mono WAV chapter files, in order. */
  readonly confirmed = output<File[]>();

  private readonly waveformHost = viewChild.required<ElementRef<HTMLDivElement>>('waveform');

  readonly duration = signal(0);
  /** Inclusive start of the kept content (trims leading silence / padding). */
  readonly rangeStart = signal(0);
  /** Exclusive end of the kept content (trims trailing silence / padding). */
  readonly rangeEnd = signal(0);
  readonly cuts = signal<number[]>([]);
  readonly loading = signal(true);
  readonly slicing = signal(false);
  readonly error = signal<string | null>(null);
  readonly playingHint = signal<string | null>(null);
  /** Playhead position on the waveform (seconds). */
  readonly playheadTime = signal(0);
  readonly isPlayingFull = computed(() => this.playingHint() === 'full');

  readonly sections = computed(() =>
    buildSectionBounds(this.duration(), this.cuts(), {
      start: this.rangeStart(),
      end: this.rangeEnd() || this.duration(),
    }),
  );
  readonly canConfirm = computed(
    () => this.sections().length >= 2 && !this.loading() && !this.slicing() && !this.error(),
  );
  readonly canAddCut = computed(() => {
    const d = this.duration();
    if (d < MIN_SECTION_GAP_S * 2) return false;
    return this.sections().some((s) => s.end - s.start >= MIN_SECTION_GAP_S * 2);
  });

  private ws: WaveSurfer | null = null;
  private regions: RegionsPlugin | null = null;
  private objectUrl: string | null = null;
  /** Separate blob URL so WaveSurfer's media element never shares preview seeks. */
  private previewUrl: string | null = null;
  private previewAudio: HTMLAudioElement | null = null;
  private previewTimeUpdate: (() => void) | null = null;
  private previewSeeked: (() => void) | null = null;
  private previewSeekFallback: number | null = null;
  private previewGeneration = 0;
  /** Skip region→cuts feedback while we rebuild markers from cuts. */
  private syncingMarkers = false;

  constructor() {
    // Mount WaveSurfer after the host is in the DOM; remount when `file` changes.
    afterRenderEffect((onCleanup) => {
      const file = this.file();
      this.waveformHost();
      untracked(() => this.mountWaveform(file));
      onCleanup(() => this.teardown());
    });
  }

  addSeparator(): void {
    if (!this.canAddCut()) return;
    const at = this.pickInsertTime();
    if (at == null) return;
    this.applyCuts([...this.cuts(), at]);
  }

  removeCut(index: number): void {
    const next = this.cuts().filter((_, i) => i !== index);
    this.applyCuts(next);
  }

  onCutTimecodeInput(index: number, event: Event): void {
    const parsed = parseTimecode((event.target as HTMLInputElement).value);
    if (parsed == null) return;
    const next = [...this.cuts()];
    next[index] = parsed;
    this.applyCuts(next);
  }

  onCutTimecodeKeydown(index: number, event: Event): void {
    this.handleTimecodeArrowKey(event, this.cuts()[index] ?? 0, (next) => {
      const cuts = [...this.cuts()];
      cuts[index] = next;
      this.applyCuts(cuts);
    });
  }

  onRangeStartInput(event: Event): void {
    const parsed = parseTimecode((event.target as HTMLInputElement).value);
    if (parsed == null) return;
    this.applyRangeStart(parsed);
  }

  onRangeEndInput(event: Event): void {
    const parsed = parseTimecode((event.target as HTMLInputElement).value);
    if (parsed == null) return;
    this.applyRangeEnd(parsed);
  }

  /** Stepper buttons: nudge the unit currently selected in the paired input. */
  nudgeRangeStartFromInput(input: HTMLInputElement, direction: 1 | -1): void {
    const unit = timecodeUnitFromInput(input);
    this.applyRangeStart(
      roundToCentisecond(Math.max(0, this.rangeStart() + direction * deltaSecondsForUnit(unit))),
    );
    this.restoreTimecodeSelection(input, unit);
  }

  nudgeRangeEndFromInput(input: HTMLInputElement, direction: 1 | -1): void {
    const unit = timecodeUnitFromInput(input);
    this.applyRangeEnd(
      roundToCentisecond(Math.max(0, this.rangeEnd() + direction * deltaSecondsForUnit(unit))),
    );
    this.restoreTimecodeSelection(input, unit);
  }

  nudgeCutFromInput(index: number, input: HTMLInputElement, direction: 1 | -1): void {
    const unit = timecodeUnitFromInput(input);
    const next = [...this.cuts()];
    next[index] = roundToCentisecond(
      Math.max(0, (next[index] ?? 0) + direction * deltaSecondsForUnit(unit)),
    );
    this.applyCuts(next);
    this.restoreTimecodeSelection(input, unit);
  }

  onRangeStartKeydown(event: Event): void {
    this.handleTimecodeArrowKey(event, this.rangeStart(), (next) => this.applyRangeStart(next));
  }

  onRangeEndKeydown(event: Event): void {
    this.handleTimecodeArrowKey(event, this.rangeEnd(), (next) => this.applyRangeEnd(next));
  }

  /**
   * ArrowUp/Down nudge the selected timecode unit (hours / minutes / seconds / centiseconds).
   */
  private handleTimecodeArrowKey(
    event: Event,
    currentSeconds: number,
    apply: (nextSeconds: number) => void,
  ): void {
    const keyEvent = event as KeyboardEvent;
    if (keyEvent.key !== 'ArrowUp' && keyEvent.key !== 'ArrowDown') return;
    keyEvent.preventDefault();
    const input = keyEvent.target as HTMLInputElement;
    const unit = timecodeUnitFromInput(input);
    const dir = keyEvent.key === 'ArrowUp' ? 1 : -1;
    apply(roundToCentisecond(Math.max(0, currentSeconds + dir * deltaSecondsForUnit(unit))));
    this.restoreTimecodeSelection(input, unit);
  }

  private restoreTimecodeSelection(input: HTMLInputElement, unit: TimecodeUnit): void {
    const [start, end] = selectionRangeForUnit(unit);
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        input.focus();
        input.setSelectionRange(start, end);
      });
    });
  }

  playSectionStart(section: SectionBound, index: number): void {
    const end = Math.min(section.end, section.start + PREVIEW_WINDOW_S);
    this.playWindow(section.start, end, `start-${index}`);
  }

  playSectionEnd(section: SectionBound, index: number): void {
    const start = Math.max(section.start, section.end - PREVIEW_WINDOW_S);
    this.playWindow(start, section.end, `end-${index}`);
  }

  /** Play / pause the full narration from the waveform cursor (drag or click to scrub). */
  toggleFullPlayback(): void {
    if (!this.ws || this.loading()) return;
    const ws = this.ws;
    if (this.playingHint() === 'full' && ws.isPlaying()) {
      ws.pause();
      this.playingHint.set(null);
      return;
    }
    this.stopSectionPreview();
    this.playingHint.set('full');
    void ws.play();
  }

  stopPlayback(): void {
    this.stopSectionPreview();
    this.ws?.pause();
    this.playingHint.set(null);
  }

  /** Stops edge-preview audio without clearing the WaveSurfer playhead. */
  private stopSectionPreview(): void {
    this.previewGeneration++;
    this.tearDownPreviewListeners();
    if (this.previewAudio) {
      this.previewAudio.pause();
    }
  }

  cancel(): void {
    this.stopPlayback();
    this.cancelled.emit();
  }

  async confirm(): Promise<void> {
    if (!this.canConfirm()) return;
    this.slicing.set(true);
    this.error.set(null);
    this.stopPlayback();
    try {
      const files = await sliceAudioFile(this.file(), this.cuts(), 'chapitre', {
        start: this.rangeStart(),
        end: this.rangeEnd(),
      });
      this.confirmed.emit(files);
    } catch {
      this.error.set('Could not split this audio file. Try another format (MP3, WAV, OGG).');
    } finally {
      this.slicing.set(false);
    }
  }

  formatTime(seconds: number): string {
    return formatTimecode(seconds);
  }

  private applyRangeStart(raw: number): void {
    const d = this.duration();
    const end = this.rangeEnd() || d;
    const start = roundToCentisecond(Math.max(0, Math.min(raw, end - MIN_SECTION_GAP_S)));
    this.rangeStart.set(start);
    this.applyCuts(this.cuts());
  }

  private applyRangeEnd(raw: number): void {
    const d = this.duration();
    const start = this.rangeStart();
    const end = roundToCentisecond(Math.max(start + MIN_SECTION_GAP_S, Math.min(raw, d)));
    this.rangeEnd.set(end);
    this.applyCuts(this.cuts());
  }

  private applyCuts(raw: number[]): void {
    const normalized = this.normalizeCuts(raw);
    this.cuts.set(normalized);
    this.syncMarkersFromCuts();
  }

  private normalizeCuts(raw: number[]): number[] {
    const sections = buildSectionBounds(this.duration(), raw, {
      start: this.rangeStart(),
      end: this.rangeEnd() || this.duration(),
    });
    return sections.slice(1).map((s) => s.start);
  }

  private pickInsertTime(): number | null {
    const rangeStart = this.rangeStart();
    const rangeEnd = this.rangeEnd() || this.duration();
    const current = this.ws?.getCurrentTime() ?? 0;
    const rounded = roundToCentisecond(current);
    if (rounded - rangeStart >= MIN_SECTION_GAP_S && rangeEnd - rounded >= MIN_SECTION_GAP_S) {
      const trial = buildSectionBounds(this.duration(), [...this.cuts(), rounded], {
        start: rangeStart,
        end: rangeEnd,
      });
      if (trial.length > this.sections().length) return rounded;
    }
    let best: SectionBound | null = null;
    for (const s of this.sections()) {
      if (!best || s.end - s.start > best.end - best.start) best = s;
    }
    if (!best || best.end - best.start < MIN_SECTION_GAP_S * 2) return null;
    return roundToCentisecond((best.start + best.end) / 2);
  }

  /**
   * Preview [start, end) on a dedicated HTMLAudioElement (not WaveSurfer).
   *
   * Critical: after "Listen to end", currentTime is near the cut. Seeking back to 0
   * is async — calling play() before 'seeked' starts at the old position (or no-ops).
   * We unlock with play() during the click gesture, pause, wait for seeked, then play.
   */
  private playWindow(start: number, end: number, hint: string): void {
    if (end <= start + 0.01 || !this.previewUrl) {
      return;
    }

    this.stopPlayback();
    const generation = ++this.previewGeneration;
    this.playingHint.set(hint);

    const audio = this.ensurePreviewAudio();
    const target = Math.max(0, start);
    const stopAt = end;

    // Mirror playhead on the waveform while the dedicated preview element plays.
    this.ws?.setTime(target);
    this.playheadTime.set(roundToCentisecond(target));

    const finish = (): void => {
      this.tearDownPreviewListeners();
      audio.pause();
      if (generation === this.previewGeneration && this.playingHint() === hint) {
        this.playingHint.set(null);
      }
    };

    const onTimeUpdate = (): void => {
      this.playheadTime.set(roundToCentisecond(audio.currentTime));
      this.ws?.setTime(audio.currentTime);
      if (audio.currentTime >= stopAt - 0.05) {
        finish();
      }
    };

    const playFromHere = (): void => {
      if (generation !== this.previewGeneration) {
        return;
      }
      this.previewTimeUpdate = onTimeUpdate;
      audio.addEventListener('timeupdate', onTimeUpdate);
      void audio.play().catch(() => finish());
    };

    const seekThenPlay = (): void => {
      if (generation !== this.previewGeneration) return;

      const delta = Math.abs(audio.currentTime - target);

      // Already on target (typical for a fresh element at t=0).
      if (delta < 0.03) {
        playFromHere();
        return;
      }

      const onSeeked = (): void => {
        if (this.previewSeeked === onSeeked) this.previewSeeked = null;
        audio.removeEventListener('seeked', onSeeked);
        if (this.previewSeekFallback != null) {
          window.clearTimeout(this.previewSeekFallback);
          this.previewSeekFallback = null;
        }
        playFromHere();
      };
      this.previewSeeked = onSeeked;
      audio.addEventListener('seeked', onSeeked);

      try {
        audio.currentTime = target;
      } catch {
        playFromHere();
        return;
      }

      // Some engines skip 'seeked' when jumping to 0 — fall back.
      this.previewSeekFallback = window.setTimeout(() => {
        this.previewSeekFallback = null;
        if (generation !== this.previewGeneration) return;
        audio.removeEventListener('seeked', onSeeked);
        if (this.previewSeeked === onSeeked) this.previewSeeked = null;
        if (Math.abs(audio.currentTime - target) < 0.15 || target < 0.05) {
          playFromHere();
        } else {
          finish();
        }
      }, 400);
    };

    // Unlock autoplay during the user gesture, then seek + play for real.
    const unlock = audio.play();
    if (unlock !== undefined) {
      void unlock
        .then(() => {
          audio.pause();
          seekThenPlay();
        })
        .catch(() => seekThenPlay());
    } else {
      seekThenPlay();
    }
  }

  private ensurePreviewAudio(): HTMLAudioElement {
    if (!this.previewAudio) {
      this.previewAudio = new Audio();
      this.previewAudio.preload = 'auto';
    }
    if (this.previewUrl && this.previewAudio.src !== this.previewUrl) {
      this.previewAudio.src = this.previewUrl;
    }
    return this.previewAudio;
  }

  private tearDownPreviewListeners(): void {
    const audio = this.previewAudio;
    if (audio && this.previewTimeUpdate) {
      audio.removeEventListener('timeupdate', this.previewTimeUpdate);
    }
    this.previewTimeUpdate = null;
    if (audio && this.previewSeeked) {
      audio.removeEventListener('seeked', this.previewSeeked);
    }
    this.previewSeeked = null;
    if (this.previewSeekFallback != null) {
      window.clearTimeout(this.previewSeekFallback);
      this.previewSeekFallback = null;
    }
  }

  private mountWaveform(file: File): void {
    this.teardownWaveformOnly();
    this.loading.set(true);
    this.error.set(null);
    this.cuts.set([]);
    this.rangeStart.set(0);
    this.rangeEnd.set(0);
    this.duration.set(0);

    const host = this.waveformHost().nativeElement;
    host.replaceChildren();

    this.objectUrl = URL.createObjectURL(file);
    // Dedicated URL for Listen previews — avoids fighting WaveSurfer's own media element.
    this.previewUrl = URL.createObjectURL(file);
    const regions = RegionsPlugin.create();
    const ws = WaveSurfer.create({
      container: host,
      url: this.objectUrl,
      height: 96,
      waveColor: '#8a8a8a',
      progressColor: '#3d5a80',
      cursorColor: '#c45c26',
      cursorWidth: 3,
      barWidth: 2,
      barGap: 1,
      barRadius: 1,
      normalize: true,
      interact: true,
      dragToSeek: true,
      autoScroll: true,
      autoCenter: true,
    });
    ws.registerPlugin(regions);

    this.ws = ws;
    this.regions = regions;
    this.playheadTime.set(0);

    // Warm the preview element so the first Listen click can seek immediately.
    const preview = this.ensurePreviewAudio();
    preview.load();

    ws.on('ready', () => {
      const d = roundToCentisecond(ws.getDuration());
      this.duration.set(d);
      this.rangeStart.set(0);
      this.rangeEnd.set(d);
      this.playheadTime.set(0);
      this.loading.set(false);
      if (this.canAddCut()) {
        const mid = roundToCentisecond(d / 2);
        this.applyCuts([mid]);
      } else {
        this.syncMarkersFromCuts();
      }
    });

    ws.on('timeupdate', (t: number) => {
      this.playheadTime.set(roundToCentisecond(t));
    });
    ws.on('seeking', (t: number) => {
      this.playheadTime.set(roundToCentisecond(t));
    });
    ws.on('interaction', (t: number) => {
      this.playheadTime.set(roundToCentisecond(t));
      // Clicking the wave while a section preview runs: stop preview, keep scrubbed position.
      if (this.playingHint() && this.playingHint() !== 'full') {
        this.stopSectionPreview();
        this.playingHint.set(null);
      }
    });
    ws.on('drag', (progress: number) => {
      const t = progress * (ws.getDuration() || 0);
      this.playheadTime.set(roundToCentisecond(t));
    });
    ws.on('pause', () => {
      if (this.playingHint() === 'full') {
        this.playingHint.set(null);
      }
    });
    ws.on('finish', () => {
      this.playingHint.set(null);
    });

    ws.on('error', () => {
      this.loading.set(false);
      this.error.set('Could not load this audio file for splitting.');
    });

    regions.on('region-updated', (region: Region) => {
      if (this.syncingMarkers) return;
      this.onMarkerMoved(region);
    });
  }

  private onMarkerMoved(region: Region): void {
    const t = roundToCentisecond(region.start);
    if (region.id === 'bound-start') {
      this.applyRangeStart(t);
      return;
    }
    if (region.id === 'bound-end') {
      this.applyRangeEnd(t);
      return;
    }
    const cutTimes = (this.regions?.getRegions() ?? [])
      .filter((r) => r.id !== region.id && r.id !== 'bound-start' && r.id !== 'bound-end')
      .map((r) => roundToCentisecond(r.start));
    this.applyCuts([...cutTimes, t]);
  }

  private syncMarkersFromCuts(): void {
    if (!this.regions) return;
    this.syncingMarkers = true;
    try {
      this.regions.clearRegions();
      const start = this.rangeStart();
      const end = this.rangeEnd() || this.duration();
      this.regions.addRegion({
        id: 'bound-start',
        start,
        end: start,
        drag: true,
        resize: false,
        color: 'rgba(61, 90, 128, 0.9)',
        content: this.formatTime(start),
      });
      this.regions.addRegion({
        id: 'bound-end',
        start: end,
        end,
        drag: true,
        resize: false,
        color: 'rgba(61, 90, 128, 0.9)',
        content: this.formatTime(end),
      });
      for (const t of this.cuts()) {
        this.regions.addRegion({
          id: `cut-${t}`,
          start: t,
          end: t,
          drag: true,
          resize: false,
          color: 'rgba(180, 60, 40, 0.85)',
          content: this.formatTime(t),
        });
      }
    } finally {
      queueMicrotask(() => {
        this.syncingMarkers = false;
      });
    }
  }

  private teardownWaveformOnly(): void {
    this.stopPlayback();
    this.ws?.destroy();
    this.ws = null;
    this.regions = null;
    if (this.previewAudio) {
      this.previewAudio.removeAttribute('src');
      this.previewAudio.load();
      this.previewAudio = null;
    }
    if (this.previewUrl) {
      URL.revokeObjectURL(this.previewUrl);
      this.previewUrl = null;
    }
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
      this.objectUrl = null;
    }
  }

  private teardown(): void {
    this.teardownWaveformOnly();
  }

}

import { Component, computed, DestroyRef, inject, input, model, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormValueControl } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { lastValueFrom } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import { blobToWavFile } from './wav-encoder.util';
import { FormsModule } from '@angular/forms';

export type TitleAudioMode = 'audio' | 'text' | 'record';

/** One microphone available to the browser. */
export interface AudioInputDevice {
  deviceId: string;
  label: string;
}

/**
 * Value of the control: the active mode plus the content of both fields (the unused
 * one is kept so the user can switch back without losing it).
 */
export interface TitleAudioSelection {
  mode: TitleAudioMode;
  /** Text to synthesize (used when mode === 'text'). */
  text: string;
  /** Uploaded or recorded audio file (used when mode === 'audio'). */
  file: File | null;
}

/**
 * Reusable title audio custom control (Signal Forms `FormValueControl`): the user either
 * uploads an audio file, records their own voice with the microphone, or provides text
 * that will be synthesized by the TTS engine at finalization. The three modes are
 * mutually exclusive; a finished recording becomes a regular audio file (mode 'audio').
 */
@Component({
  selector: 'app-title-audio-input',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTooltipModule,
    FormsModule,
  ],
  templateUrl: './title-audio-input.component.html',
  styleUrl: './title-audio-input.component.scss',
})
export class TitleAudioInputComponent implements FormValueControl<TitleAudioSelection | null> {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  readonly label = input('Title audio');

  readonly value = model.required<TitleAudioSelection | null>();

  protected readonly mode = computed(() => this.value()?.mode ?? 'text');
  protected readonly text = computed(() => this.value()?.text ?? '');
  protected readonly audioFile = computed(() => this.value()?.file ?? null);

  protected readonly previewing = signal(false);
  readonly audioUrl = signal<string | null>(null);
  readonly hasAudio = signal(false);

  readonly recording = signal(false);
  protected readonly recordingSeconds = signal(0);
  readonly recordError = signal<string | null>(null);
  readonly recordingTimeLabel = computed(() => {
    const s = this.recordingSeconds();
    const mm = String(Math.floor(s / 60)).padStart(2, '0');
    const ss = String(s % 60).padStart(2, '0');
    return `${mm}:${ss}`;
  });

  /** Microphones detected by the browser (labels only available after permission). */
  readonly devices = signal<AudioInputDevice[]>([]);
  /** Selected microphone deviceId, `null` = system default. */
  readonly selectedDeviceId = signal<string | null>(null);
  readonly trackMuted = signal(false);

  /** Selected TTS provider for the preview: FREE | OPENAI | ELEVENLABS */
  selectedProvider = signal<'FREE' | 'OPENAI' | 'ELEVENLABS'>('FREE');

  private recorder: MediaRecorder | null = null;
  private stream: MediaStream | null = null;
  private chunks: BlobPart[] = [];
  private timerId: number | null = null;

  onModeChange(next: TitleAudioMode | null | undefined): void {
    if (!next || next === this.value()?.mode) return;
    this.revokePreview();
    this.recordError.set(null);
    if (next !== 'record') this.cancelRecording();
    this.value.update((v) => ({ mode: next, text: v?.text ?? '', file: v?.file ?? null }));
  }

  onTextChange(text: string): void {
    this.revokePreview();
    this.value.update((v) => ({ mode: v?.mode ?? 'text', text, file: v?.file ?? null }));
  }

  onFileSelected(file: File | null): void {
    this.revokePreview();
    this.value.update((v) => ({ mode: v?.mode ?? 'text', text: v?.text ?? '', file }));
    if (file) {
      this.audioUrl.set(URL.createObjectURL(file));
      this.hasAudio.set(true);
    } else {
      this.hasAudio.set(false);
    }
  }

  clearFile(): void {
    this.onFileSelected(null);
  }

  async startRecording(): Promise<void> {
    if (this.recording()) return;
    this.recordError.set(null);
    try {
      const deviceId = this.selectedDeviceId();
      const constraints: MediaStreamConstraints = deviceId
        ? { audio: { deviceId: { exact: deviceId } } }
        : { audio: true };
      this.stream = await navigator.mediaDevices.getUserMedia(constraints);
      const track = (this.stream.getAudioTracks?.() ?? [])[0];
      if (track) {
        this.trackMuted.set(track.muted);
        track.onmute = () => this.trackMuted.set(true);
        track.onunmute = () => this.trackMuted.set(false);
      }
      await this.loadDevices();
      const mimeType = MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : '';
      this.recorder = new MediaRecorder(this.stream, mimeType ? { mimeType } : undefined);
      this.chunks = [];
      this.recorder.ondataavailable = (event: BlobEvent) => {
        if (event.data.size > 0) this.chunks.push(event.data);
      };
      this.recorder.onstop = () => this.finishRecording();
      this.recorder.start();
      this.recording.set(true);
      this.recordingSeconds.set(0);
      this.timerId = window.setInterval(() => this.recordingSeconds.update((s) => s + 1), 1000);
    } catch {
      this.stopTracks();
      this.recorder = null;
      this.recordError.set('Microphone unavailable or permission denied.');
    }
  }

  stopRecording(): void {
    if (this.recorder && this.recorder.state !== 'inactive') this.recorder.stop();
  }

  private async finishRecording(): Promise<void> {
    this.recording.set(false);
    if (this.timerId !== null) {
      window.clearInterval(this.timerId);
      this.timerId = null;
    }
    this.recordingSeconds.set(0);
    this.stopTracks();
    const type = this.recorder?.mimeType || 'audio/webm';
    const blob = new Blob(this.chunks, { type });
    // Convert the recorded WebM/Opus audio to WAV so the backend's Java
    // AudioSystem can decode it during Lunii pack format conversion.
    const file = await blobToWavFile(blob, 'recording.wav');
    this.revokePreview();
    this.value.update((v) => ({ mode: 'audio', text: v?.text ?? '', file }));
    this.audioUrl.set(URL.createObjectURL(file));
    this.hasAudio.set(true);
    this.recorder = null;
    this.chunks = [];
  }

  private cancelRecording(): void {
    if (this.recorder && this.recorder.state !== 'inactive') {
      this.recorder.onstop = null;
      try {
        this.recorder.stop();
      } catch {
        /* recorder already stopped */
      }
    }
    this.recorder = null;
    this.chunks = [];
    this.stopTracks();
    this.recording.set(false);
    if (this.timerId !== null) {
      window.clearInterval(this.timerId);
      this.timerId = null;
    }
    this.recordingSeconds.set(0);
  }

  onDeviceChange(deviceId: string | null | undefined): void {
    if (!deviceId) {
      this.selectedDeviceId.set(null);
    } else {
      this.selectedDeviceId.set(deviceId);
    }
  }

  private async loadDevices(): Promise<void> {
    try {
      const inputs = await navigator.mediaDevices.enumerateDevices();
      const mics = inputs.filter((device) => device.kind === 'audioinput');
      if (mics.length === 0) return;
      this.devices.set(
        mics.map((device) => ({
          deviceId: device.deviceId,
          label: device.label || `Microphone ${device.deviceId.slice(0, 8)}`,
        })),
      );
      if (this.selectedDeviceId() && !mics.some((d) => d.deviceId === this.selectedDeviceId())) {
        this.selectedDeviceId.set(null);
      }
    } catch {
      this.devices.set([]);
    }
  }

  private stopTracks(): void {
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
  }

  protected async previewTts(): Promise<void> {
    const value = this.text().trim();
    if (!value || this.previewing()) return;
    this.previewing.set(true);
    try {
      const blob = await lastValueFrom(
        this.http.get(`${environment.apiUrl}/tts/preview`, {
          params: { text: value, provider: this.selectedProvider() },
          responseType: 'blob',
        }),
      );
      this.revokePreview();
      this.audioUrl.set(URL.createObjectURL(blob));
      this.hasAudio.set(true);
    } catch {
      this.hasAudio.set(false);
    } finally {
      this.previewing.set(false);
    }
  }

  private revokePreview(): void {
    const url = this.audioUrl();
    if (url) {
      URL.revokeObjectURL(url);
      this.audioUrl.set(null);
    }
    this.hasAudio.set(false);
  }

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.cancelRecording();
      this.revokePreview();
    });
  }
}

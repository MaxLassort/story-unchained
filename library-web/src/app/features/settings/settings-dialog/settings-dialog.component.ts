import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { Settings, TtsVoice } from '../../../core/models';
import { DesktopService } from '../../../core/services/desktop.service';
import { SettingsService } from '../../../core/services/settings.service';
import { SyncService } from '../../../core/services/sync.service';

type TargetType = 'AUTO' | 'RAW' | 'FS';

type TtsProvider = 'FREE' | 'OPENAI' | 'ELEVENLABS';

interface DialogData {
  settings: Settings;
}

interface SettingsFormModel {
  libraryPath: string;
  target: TargetType;
  unofficialDbPath: string;
  ttsProvider: TtsProvider;
  ttsOpenAiApiKey: string;
  ttsElevenLabsApiKey: string;
  ttsVoice: string;
  ttsLang: string;
}

const TARGET_OPTIONS: { label: string; value: TargetType }[] = [
  { label: 'Auto (detect when plugged)', value: 'AUTO' },
  { label: 'V1 / RAW', value: 'RAW' },
  { label: 'V2 / FS', value: 'FS' },
];

const TTS_PROVIDER_OPTIONS: { label: string; value: TtsProvider }[] = [
  { label: 'Free (Google Translate)', value: 'FREE' },
  { label: 'OpenAI', value: 'OPENAI' },
  { label: 'ElevenLabs', value: 'ELEVENLABS' },
];

const TTS_LANG_OPTIONS: { label: string; value: string }[] = [
  { label: 'Français', value: 'fr' },
  { label: 'English', value: 'en' },
  { label: 'Deutsch', value: 'de' },
  { label: 'Español', value: 'es' },
  { label: 'Italiano', value: 'it' },
  { label: 'Português', value: 'pt' },
  { label: 'Nederlands', value: 'nl' },
  { label: 'Polski', value: 'pl' },
  { label: 'Русский', value: 'ru' },
  { label: '日本語', value: 'ja' },
  { label: '中文', value: 'zh' },
  { label: 'العربية', value: 'ar' },
];

const DEFAULT_UNOFFICIAL_DB_PATH = '~/.studio/db/unofficial.json';
const DEFAULT_TTS_LANG = 'fr';

function settingsToTarget(s: Settings): TargetType {
  if (s.targetDeviceType === 'RAW') return 'RAW';
  if (s.targetDeviceType === 'FS') return 'FS';
  return 'AUTO';
}

function settingsToTtsProvider(s: Settings): TtsProvider {
  if (s.ttsProvider === 'OPENAI') return 'OPENAI';
  if (s.ttsProvider === 'ELEVENLABS') return 'ELEVENLABS';
  return 'FREE';
}

function settingsToModel(s: Settings): SettingsFormModel {
  return {
    libraryPath: s.libraryPath ?? '',
    target: settingsToTarget(s),
    unofficialDbPath: s.unofficialDbPath ?? '',
    ttsProvider: settingsToTtsProvider(s),
    ttsOpenAiApiKey: s.ttsOpenAiApiKey ?? '',
    ttsElevenLabsApiKey: s.ttsElevenLabsApiKey ?? '',
    ttsVoice: s.ttsVoice ?? '',
    ttsLang: s.ttsLang ?? DEFAULT_TTS_LANG,
  };
}

@Component({
  selector: 'app-settings-dialog',
  imports: [
    FormField,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './settings-dialog.component.html',
  styleUrl: './settings-dialog.component.scss',
})
export class SettingsDialogComponent {
  protected readonly data = inject<DialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<SettingsDialogComponent, Settings>>(MatDialogRef);
  private readonly desktop = inject(DesktopService);
  private readonly settingsService = inject(SettingsService);
  private readonly syncService = inject(SyncService);

  protected readonly targetOptions = TARGET_OPTIONS;
  protected readonly ttsProviderOptions = TTS_PROVIDER_OPTIONS;
  protected readonly ttsLangOptions = TTS_LANG_OPTIONS;
  protected readonly defaultUnofficialDbPath = DEFAULT_UNOFFICIAL_DB_PATH;

  protected readonly syncing = this.syncService.syncing;

  protected readonly model = signal<SettingsFormModel>(settingsToModel(this.data.settings));
  protected readonly form = form(this.model);

  protected readonly voices = signal<TtsVoice[]>([]);
  protected readonly voicesLoading = signal(false);
  protected readonly voicesError = signal<string | null>(null);
  protected readonly voicesFallback = signal(false);

  private readonly originalLibraryPath = this.data.settings.libraryPath ?? '';
  private readonly originalUnofficialDbPath = this.data.settings.unofficialDbPath ?? '';

  protected readonly libraryChanged = computed(
    () => this.model().libraryPath !== this.originalLibraryPath,
  );
  protected readonly unofficialDbChanged = computed(
    () => this.model().unofficialDbPath !== this.originalUnofficialDbPath,
  );

  constructor() {
    this.refreshVoices();
  }

  /** API key input bound to the currently selected provider. */
  protected readonly currentApiKey = computed(() => {
    const m = this.model();
    return m.ttsProvider === 'ELEVENLABS' ? m.ttsElevenLabsApiKey : m.ttsOpenAiApiKey;
  });

  /** Called when the provider or its API key changes in the form. */
  protected refreshVoices(): void {
    const provider = this.model().ttsProvider;
    const apiKey = this.currentApiKey().trim();
    if (provider === 'FREE') {
      this.voices.set([]);
      this.voicesError.set(null);
      this.voicesFallback.set(false);
      return;
    }
    if (provider === 'ELEVENLABS' && !apiKey) {
      this.voices.set([]);
      this.voicesError.set('Enter your API key to list voices');
      this.voicesFallback.set(false);
      return;
    }
    void this.loadVoices(provider);
  }

  private async loadVoices(provider: TtsProvider): Promise<void> {
    this.voicesLoading.set(true);
    this.voicesError.set(null);
    this.voicesFallback.set(false);
    try {
      const response = await this.settingsService.getVoices(provider);
      this.voices.set(response.voices);
      this.voicesFallback.set(response.fallback);
    } catch {
      this.voices.set([]);
      this.voicesError.set('Could not load voices');
    } finally {
      this.voicesLoading.set(false);
    }
  }

  protected async browseLibraryPath(): Promise<void> {
    const selected = await this.desktop.selectDirectory({
      title: 'Select library folder',
      defaultPath: this.model().libraryPath || undefined,
      buttonLabel: 'Select folder',
    });
    if (selected) {
      this.model.update((m) => ({ ...m, libraryPath: selected }));
    }
  }

  protected async browseUnofficialDb(): Promise<void> {
    const selected = await this.desktop.selectFile({
      title: 'Select Studio unofficial DB file',
      defaultPath: this.model().unofficialDbPath || undefined,
      buttonLabel: 'Select file',
      filters: [{ name: 'JSON', extensions: ['json'] }],
    });
    if (selected) {
      this.model.update((m) => ({ ...m, unofficialDbPath: selected }));
    }
  }

  protected async startSync(): Promise<void> {
    await this.syncService.startSync();
  }

  protected save(): void {
    const m = this.model();
    const next: Settings = {
      libraryPath: m.libraryPath,
      unofficialDbPath: m.unofficialDbPath.trim() || null,
      targetDeviceType: m.target === 'AUTO' ? null : m.target,
      ttsProvider: m.ttsProvider === 'FREE' ? null : m.ttsProvider,
      ttsOpenAiApiKey: m.ttsOpenAiApiKey.trim() || null,
      ttsElevenLabsApiKey: m.ttsElevenLabsApiKey.trim() || null,
      ttsVoice: m.ttsVoice.trim() || null,
      ttsLang: m.ttsLang || DEFAULT_TTS_LANG,
    };
    this.dialogRef.close(next);
  }
}
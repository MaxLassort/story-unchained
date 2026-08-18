import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { SettingsDialogComponent } from './settings-dialog.component';
import { SettingsService } from '../../../core/services/settings.service';
import type { Settings, TtsVoicesResponse } from '../../../core/models';

function baseSettings(overrides: Partial<Settings> = {}): Settings {
  return {
    libraryPath: '/packs',
    unofficialDbPath: null,
    targetDeviceType: null,
    ttsProvider: null,
    ttsOpenAiApiKey: null,
    ttsElevenLabsApiKey: null,
    ttsVoice: null,
    ttsLang: null,
    ...overrides,
  };
}

const OPENAI_VOICES: TtsVoicesResponse = {
  provider: 'OPENAI',
  voices: [
    { id: 'alloy', name: 'Alloy' },
    { id: 'nova', name: 'Nova' },
  ],
  fallback: false,
};

const ELEVENLABS_VOICES: TtsVoicesResponse = {
  provider: 'ELEVENLABS',
  voices: [
    { id: '21m00Tcm4TlvDq8ikWAM', name: 'Rachel' },
    { id: 'EXAVITQu4vr4xnSDxMaL', name: 'Bella' },
  ],
  fallback: false,
};

async function createDialog(settings: Settings) {
  const dialogRef = { close: vi.fn() };
  const settingsService = {
    getVoices: vi.fn(async (provider: string | null) =>
      provider === 'ELEVENLABS' ? ELEVENLABS_VOICES : OPENAI_VOICES,
    ),
  };
  await TestBed.configureTestingModule({
    imports: [SettingsDialogComponent],
    providers: [
      { provide: SettingsService, useValue: settingsService },
      { provide: MAT_DIALOG_DATA, useValue: { settings } },
      { provide: MatDialogRef, useValue: dialogRef },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(SettingsDialogComponent);
  fixture.detectChanges();
  return { fixture, dialogRef, settingsService };
}

describe('SettingsDialogComponent', () => {
  beforeEach(() => vi.clearAllMocks());

  it('renders the TTS section with the Free provider selected by default', async () => {
    const { fixture } = await createDialog(baseSettings());
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('.settings-dialog__section-title')?.textContent).toContain(
      'Text-to-speech',
    );
    expect(el.querySelectorAll('mat-select').length).toBe(3);
    expect(el.querySelector('input[placeholder="sk-…"]')).toBeFalsy();
    expect(el.querySelector('input[placeholder="sk_…"]')).toBeFalsy();
  });

  it('shows the OpenAI key input and voice fields when OpenAI is selected', async () => {
    const { fixture } = await createDialog(
      baseSettings({
        ttsProvider: 'OPENAI',
        ttsOpenAiApiKey: 'sk-test',
        ttsVoice: 'alloy',
      }),
    );
    const el = fixture.nativeElement as HTMLElement;

    const keyInput = el.querySelector('input[placeholder="sk-…"]') as HTMLInputElement;
    expect(keyInput).toBeTruthy();
    expect(keyInput.value).toBe('sk-test');
    expect(el.querySelector('input[placeholder="sk_…"]')).toBeFalsy();
    expect(el.querySelectorAll('mat-select').length).toBe(4);
  });

  it('shows the ElevenLabs key input when ElevenLabs is selected', async () => {
    const { fixture } = await createDialog(
      baseSettings({
        ttsProvider: 'ELEVENLABS',
        ttsElevenLabsApiKey: 'el-key',
        ttsVoice: 'Rachel',
      }),
    );
    const el = fixture.nativeElement as HTMLElement;

    const keyInput = el.querySelector('input[placeholder="sk_…"]') as HTMLInputElement;
    expect(keyInput).toBeTruthy();
    expect(keyInput.value).toBe('el-key');
    expect(el.querySelector('input[placeholder="sk-…"]')).toBeFalsy();
  });

  it('loads the voice list for the selected provider', async () => {
    const { fixture, settingsService } = await createDialog(
      baseSettings({ ttsProvider: 'OPENAI', ttsOpenAiApiKey: 'sk-test' }),
    );

    await vi.waitFor(() => {
      expect(settingsService.getVoices).toHaveBeenCalledWith('OPENAI');
    });
    await vi.waitFor(() => {
      expect(fixture.componentInstance['voices']().length).toBe(2);
    });
  });

  it('reloads the voice list when switching provider', async () => {
    const { fixture, settingsService } = await createDialog(
      baseSettings({ ttsProvider: 'OPENAI', ttsOpenAiApiKey: 'sk-test' }),
    );
    await vi.waitFor(() => {
      expect(settingsService.getVoices).toHaveBeenCalledWith('OPENAI');
    });

    const comp = fixture.componentInstance;
    comp['model'].update((m) => ({
      ...m,
      ttsProvider: 'ELEVENLABS',
      ttsElevenLabsApiKey: 'el-key',
    }));
    comp['refreshVoices']();
    fixture.detectChanges();

    await vi.waitFor(() => {
      expect(settingsService.getVoices).toHaveBeenCalledWith('ELEVENLABS');
    });
    await vi.waitFor(() => {
      expect(comp['voices']().length).toBe(2);
    });
  });

  it('saves both API keys independently', async () => {
    const { fixture, dialogRef } = await createDialog(
      baseSettings({
        ttsProvider: 'OPENAI',
        ttsOpenAiApiKey: 'sk-test',
        ttsElevenLabsApiKey: 'el-key',
        ttsVoice: 'alloy',
        ttsLang: 'fr',
      }),
    );
    const el = fixture.nativeElement as HTMLElement;

    const keyInput = el.querySelector('input[placeholder="sk-…"]') as HTMLInputElement;
    keyInput.value = 'sk-new';
    keyInput.dispatchEvent(new Event('input'));

    const saveButton = [...el.querySelectorAll('button')].find((b) =>
      b.textContent?.trim().includes('Save'),
    ) as HTMLButtonElement;
    saveButton.click();

    expect(dialogRef.close).toHaveBeenCalledWith(
      expect.objectContaining({
        ttsProvider: 'OPENAI',
        ttsOpenAiApiKey: 'sk-new',
        ttsElevenLabsApiKey: 'el-key',
        ttsVoice: 'alloy',
        ttsLang: 'fr',
      }),
    );
  });

  it('maps Free provider to null when saving', async () => {
    const { fixture, dialogRef } = await createDialog(
      baseSettings({ ttsProvider: 'ELEVENLABS', ttsElevenLabsApiKey: 'el-key' }),
    );
    const el = fixture.nativeElement as HTMLElement;
    const saveButton = [...el.querySelectorAll('button')].find((b) =>
      b.textContent?.trim().includes('Save'),
    ) as HTMLButtonElement;
    saveButton.click();

    expect(dialogRef.close).toHaveBeenCalledWith(
      expect.objectContaining({ ttsProvider: 'ELEVENLABS', ttsElevenLabsApiKey: 'el-key' }),
    );
  });

  it('defaults ttsLang to fr when saving without a language', async () => {
    const { fixture, dialogRef } = await createDialog(baseSettings());
    const el = fixture.nativeElement as HTMLElement;
    const saveButton = [...el.querySelectorAll('button')].find((b) =>
      b.textContent?.trim().includes('Save'),
    ) as HTMLButtonElement;
    saveButton.click();

    expect(dialogRef.close).toHaveBeenCalledWith(
      expect.objectContaining({ ttsProvider: null, ttsLang: 'fr' }),
    );
  });
});
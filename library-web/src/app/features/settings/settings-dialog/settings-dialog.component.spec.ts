import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { SettingsDialogComponent } from './settings-dialog.component';
import type { Settings } from '../../../core/models';

function baseSettings(overrides: Partial<Settings> = {}): Settings {
  return {
    libraryPath: '/packs',
    unofficialDbPath: null,
    targetDeviceType: null,
    ttsProvider: null,
    ttsApiKey: null,
    ttsVoice: null,
    ...overrides,
  };
}

async function createDialog(settings: Settings) {
  const dialogRef = { close: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [SettingsDialogComponent],
    providers: [
      provideHttpClient(),
      { provide: MAT_DIALOG_DATA, useValue: { settings } },
      { provide: MatDialogRef, useValue: dialogRef },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(SettingsDialogComponent);
  fixture.detectChanges();
  return { fixture, dialogRef };
}

describe('SettingsDialogComponent', () => {
  beforeEach(() => vi.clearAllMocks());

  it('renders the TTS section with the Free provider selected by default', async () => {
    const { fixture } = await createDialog(baseSettings());
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('.settings-dialog__section-title')?.textContent).toContain(
      'Text-to-speech',
    );
    expect(el.querySelectorAll('mat-select').length).toBe(2);
    expect(el.querySelector('input[placeholder="sk-…"]')).toBeFalsy();
  });

  it('shows API key and voice fields when a paid provider is selected', async () => {
    const { fixture } = await createDialog(
      baseSettings({ ttsProvider: 'OPENAI', ttsApiKey: 'sk-test', ttsVoice: 'alloy' }),
    );
    const el = fixture.nativeElement as HTMLElement;

    const keyInput = el.querySelector('input[placeholder="sk-…"]') as HTMLInputElement;
    expect(keyInput).toBeTruthy();
    expect(keyInput.value).toBe('sk-test');
    const voiceInput = el.querySelector('input[placeholder="alloy"]') as HTMLInputElement;
    expect(voiceInput).toBeTruthy();
    expect(voiceInput.value).toBe('alloy');
  });

  it('saves TTS fields back to Settings', async () => {
    const { fixture, dialogRef } = await createDialog(
      baseSettings({ ttsProvider: 'OPENAI', ttsApiKey: 'sk-test', ttsVoice: 'alloy' }),
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
        ttsApiKey: 'sk-new',
        ttsVoice: 'alloy',
      }),
    );
  });

  it('maps Free provider to null when saving', async () => {
    const { fixture, dialogRef } = await createDialog(
      baseSettings({ ttsProvider: 'ELEVENLABS', ttsApiKey: 'el-key' }),
    );
    const el = fixture.nativeElement as HTMLElement;
    const saveButton = [...el.querySelectorAll('button')].find((b) =>
      b.textContent?.trim().includes('Save'),
    ) as HTMLButtonElement;
    saveButton.click();

    expect(dialogRef.close).toHaveBeenCalledWith(
      expect.objectContaining({ ttsProvider: 'ELEVENLABS', ttsApiKey: 'el-key' }),
    );
  });
});

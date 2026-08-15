import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import type { Settings } from '../../../core/models';

type TargetType = 'AUTO' | 'RAW' | 'FS';

interface DialogData {
  settings: Settings;
}

const TARGET_OPTIONS: { label: string; value: TargetType }[] = [
  { label: 'Auto (detect when plugged)', value: 'AUTO' },
  { label: 'V1 / RAW', value: 'RAW' },
  { label: 'V2 / FS', value: 'FS' },
];

function settingsToTarget(s: Settings): TargetType {
  if (s.targetDeviceType === 'RAW') return 'RAW';
  if (s.targetDeviceType === 'FS') return 'FS';
  return 'AUTO';
}

@Component({
  selector: 'app-settings-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatIconModule],
  templateUrl: './settings-dialog.component.html',
  styleUrl: './settings-dialog.component.scss',
})
export class SettingsDialogComponent {
  protected readonly data = inject<DialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<SettingsDialogComponent, Settings>>(MatDialogRef);

  protected readonly targetOptions = TARGET_OPTIONS;

  protected libraryPath: string;
  protected target: TargetType;
  private readonly originalLibraryPath: string;

  constructor() {
    const s = this.data.settings;
    this.libraryPath = s.libraryPath ?? '';
    this.target = settingsToTarget(s);
    this.originalLibraryPath = this.libraryPath;
  }

  protected get libraryChanged(): boolean {
    return this.libraryPath !== this.originalLibraryPath;
  }

  protected save(): void {
    const next: Settings = {
      libraryPath: this.libraryPath,
      unofficialDbPath: this.data.settings.unofficialDbPath,
      targetDeviceType: this.target === 'AUTO' ? null : this.target,
    };
    this.dialogRef.close(next);
  }
}

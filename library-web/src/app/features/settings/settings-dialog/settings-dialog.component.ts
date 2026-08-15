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
import type { Settings } from '../../../core/models';
import { DesktopService } from '../../../core/services/desktop.service';
import { SyncService } from '../../../core/services/sync.service';

type TargetType = 'AUTO' | 'RAW' | 'FS';

interface DialogData {
  settings: Settings;
}

interface SettingsFormModel {
  libraryPath: string;
  target: TargetType;
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

function settingsToModel(s: Settings): SettingsFormModel {
  return {
    libraryPath: s.libraryPath ?? '',
    target: settingsToTarget(s),
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
  private readonly syncService = inject(SyncService);

  protected readonly targetOptions = TARGET_OPTIONS;

  protected readonly syncing = this.syncService.syncing;

  protected readonly model = signal<SettingsFormModel>(settingsToModel(this.data.settings));
  protected readonly form = form(this.model);

  private readonly originalLibraryPath = this.data.settings.libraryPath ?? '';

  protected readonly libraryChanged = computed(
    () => this.model().libraryPath !== this.originalLibraryPath,
  );

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

  protected async startSync(): Promise<void> {
    await this.syncService.startSync();
  }

  protected save(): void {
    const m = this.model();
    const next: Settings = {
      libraryPath: m.libraryPath,
      targetDeviceType: m.target === 'AUTO' ? null : m.target,
    };
    this.dialogRef.close(next);
  }
}

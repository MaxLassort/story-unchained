import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { lastValueFrom } from 'rxjs';
import type { Pack } from '../../../../core/models';
import { FormatBadgeComponent } from '../format-badge/format-badge.component';
import { PackDeleteDialogComponent } from '../pack-delete-dialog/pack-delete-dialog.component';
import { PackConvertDialogComponent } from '../pack-convert-dialog/pack-convert-dialog.component';
import { CopyPackDialogComponent } from '../copy-pack-dialog/copy-pack-dialog.component';
import { PacksService } from '../../../../core/services/packs.service';
import { DevicesService } from '../../../../core/services/devices.service';
import { SnackbarService } from '../../../../core/services/snackbar.service';
import { SseService } from '../../../../core/services/sse.service';
import { LoadingOverlayComponent } from '../../../../shared/components/loading-overlay/loading-overlay.component';

@Component({
  selector: 'app-pack-card',
  imports: [MatButtonModule, MatDividerModule, MatIconModule, MatMenuModule, FormatBadgeComponent, LoadingOverlayComponent],
  templateUrl: './pack-card.component.html',
  styleUrl: './pack-card.component.scss',
})
export class PackCardComponent {
  readonly pack = input.required<Pack>();

  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly packsService = inject(PacksService);
  private readonly devicesService = inject(DevicesService);
  private readonly snackbar = inject(SnackbarService);
  private readonly sseService = inject(SseService);

  protected readonly flipped = signal(false);
  protected readonly imgError = signal(false);
  protected readonly converting = signal(false);
  protected readonly copyingToDevice = signal(false);
  protected readonly deleting = signal(false);

  constructor() {
    effect(() => {
      const conv = this.sseService.deviceEvent().conversion;
      if (!conv || conv.packId !== this.pack().id || !this.converting()) return;
      if (conv.status === 'DONE') {
        this.packsService.refresh();
        this.snackbar.success('Pack converted');
        this.converting.set(false);
      }
      if (conv.status === 'FAILED') {
        this.snackbar.error(conv.message ?? 'Conversion failed');
        this.converting.set(false);
      }
    }, { allowSignalWrites: true });
  }

  protected readonly formats = computed(() =>
    [...new Set(this.pack().variants.map((v) => v.format))],
  );

  protected readonly thumbnailUrl = computed(() => this.pack().metadata.thumbnail ?? '');
  protected readonly title = computed(() => this.pack().metadata.title ?? 'Untitled');
  protected readonly description = computed(() => this.pack().metadata.description ?? '');
  protected readonly isOfficial = computed(() => this.pack().metadata.official);
  protected readonly ageRange = computed(() => {
    const { ageMin, ageMax } = this.pack().metadata;
    if (ageMin != null && ageMax != null) return `${ageMin}${ageMax} ans`;
    if (ageMin != null) return `Dès ${ageMin} ans`;
    if (ageMax != null) return `Jusqu’à ${ageMax} ans`;
    return 'Âge non renseigné';
  });

  protected toggleFlip(): void {
    this.flipped.update((v) => !v);
  }

  protected edit(): void {
    void this.router.navigate(['/packs', this.pack().id]);
  }

  protected async delete(): Promise<void> {
    const ref = this.dialog.open(PackDeleteDialogComponent, {
      data: { title: this.title(), id: this.pack().id },
      width: '400px',
    });
    const confirmed = await lastValueFrom(ref.afterClosed());
    if (confirmed) {
      this.deleting.set(true);
      try {
        await this.packsService.deletePack(this.pack().id);
        this.snackbar.success('Pack deleted');
      } catch {
        this.snackbar.error('Failed to delete pack');
      } finally {
        this.deleting.set(false);
      }
    }
  }

  protected convert(): void {
    const ref = this.dialog.open(PackConvertDialogComponent, {
      data: { title: this.title(), id: this.pack().id },
      width: '400px',
    });
    ref.afterClosed().subscribe(async (result) => {
      if (result) {
        this.sseService.connect();
        this.converting.set(true);
        await this.packsService.convert(this.pack().id, result);
        this.snackbar.success('Conversion started');
      }
    });
  }

  protected async copyToDevice(): Promise<void> {
    if (this.pack().metadata.official) {
      const ref = this.dialog.open(CopyPackDialogComponent, {
        data: { title: this.title(), id: this.pack().id },
        width: '400px',
      });
      const confirmed = await lastValueFrom(ref.afterClosed());
      if (!confirmed) return;
    }
    try {
      this.copyingToDevice.set(true);
      const res = await this.devicesService.copyToDevice(this.pack().id);
      if (res.ok) {
        this.snackbar.success('Copied to device');
      } else {
        this.snackbar.error(res.error ?? 'Copy failed');
      }
    } catch {
      this.snackbar.error('Device not connected');
    } finally {
      this.copyingToDevice.set(false);
    }
  }
}

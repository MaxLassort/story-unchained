import { Component, computed, inject, signal } from '@angular/core';
import { CdkCopyToClipboard } from '@angular/cdk/clipboard';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SseService } from '../../../core/services/sse.service';
import { DevicesService } from '../../../core/services/devices.service';
import { PacksService } from '../../../core/services/packs.service';
import { SnackbarService } from '../../../core/services/snackbar.service';
import { LoadingOverlayComponent } from '../../../shared/components/loading-overlay/loading-overlay.component';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-device-panel',
  imports: [CdkCopyToClipboard, MatButtonModule, MatDividerModule, MatIconModule, MatListModule, MatProgressBarModule, MatTooltipModule, LoadingOverlayComponent],
  templateUrl: './device-panel.component.html',
  styleUrl: './device-panel.component.scss',
})
export class DevicePanelComponent {
  private readonly sseService = inject(SseService);
  private readonly devicesService = inject(DevicesService);
  private readonly packsService = inject(PacksService);
  private readonly snackbar = inject(SnackbarService);

  protected readonly device = computed(() => this.sseService.deviceEvent().device);
  protected readonly packs = computed(() => this.sseService.deviceEvent().packs);
  protected readonly isPlugged = this.sseService.isPlugged;
  protected readonly copying = signal(false);
  protected readonly deleting = signal(false);

  protected readonly driverLabel = computed(() => {
    const d = this.device().driver;
    if (!d) return '';
    return d === 'raw' ? 'RAW' : 'FS';
  });

  protected readonly storagePercent = computed(() => {
    const s = this.device().storage;
    if (!s || s.size === 0) return 0;
    return Math.round((s.taken / s.size) * 100);
  });

  protected readonly usedGb = computed(() => this.formatGb(this.device().storage?.taken));
  protected readonly freeGb = computed(() => this.formatGb(this.device().storage?.free));
  protected readonly totalGb = computed(() => this.formatGb(this.device().storage?.size));

  constructor() {
    this.sseService.connect();
  }

  protected thumbnailUrl(dp: { uuid: string; thumbnail: string | null }): string {
    if (dp.thumbnail) return dp.thumbnail;
    return `${environment.apiUrl}/packs/${dp.uuid}/thumbnail`;
  }

  protected async deletePack(uuid: string): Promise<void> {
    if (!confirm(`Delete pack from device?\n${uuid}`)) return;
    this.deleting.set(true);
    try {
      await this.devicesService.deleteFromDevice(uuid);
      this.sseService.connect();
      this.snackbar.success('Removed from device');
    } catch {
      this.snackbar.error('Failed to remove from device');
    } finally {
      this.deleting.set(false);
    }
  }

  protected async copyToLibrary(uuid: string): Promise<void> {
    this.copying.set(true);
    try {
      await this.devicesService.copyToLibrary(uuid);
      this.packsService.refresh();
      this.snackbar.success('Copied to library');
    } catch {
      this.snackbar.error('Failed to copy to library');
    } finally {
      this.copying.set(false);
    }
  }

  private formatGb(bytes: number | undefined | null): string {
    if (bytes == null) return '—';
    return (bytes / 1_073_741_824).toFixed(1) + ' GB';
  }

  protected formatSize(bytes: number): string {
    if (bytes < 1_048_576) return (bytes / 1024).toFixed(0) + ' KB';
    return (bytes / 1_048_576).toFixed(1) + ' MB';
  }
}

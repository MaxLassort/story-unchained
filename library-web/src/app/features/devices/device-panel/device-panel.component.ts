import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
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
import { LanguageService } from '../../../core/services/language.service';
import { LoadingOverlayComponent } from '../../../shared/components/loading-overlay/loading-overlay.component';
import { TranslatePipe, translate } from '../../../core/pipes/translate.pipe';

@Component({
  selector: 'app-device-panel',
  imports: [CdkCopyToClipboard, MatButtonModule, MatDividerModule, MatIconModule, MatListModule, MatProgressBarModule, MatTooltipModule, LoadingOverlayComponent, TranslatePipe],
  templateUrl: './device-panel.component.html',
  styleUrl: './device-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DevicePanelComponent {
  private readonly sseService = inject(SseService);
  private readonly devicesService = inject(DevicesService);
  private readonly packsService = inject(PacksService);
  private readonly snackbar = inject(SnackbarService);
  private readonly lang = inject(LanguageService);

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
    return dp.thumbnail ?? '';
  }

  protected async deletePack(uuid: string): Promise<void> {
    if (!confirm(`Delete pack from device?\n${uuid}`)) return;
    this.deleting.set(true);
    try {
      await this.devicesService.deleteFromDevice(uuid);
      this.sseService.connect();
      this.snackbar.success(translate('Removed from device', this.lang.currentLang()));
    } catch {
      this.snackbar.error(translate('Failed to remove from device', this.lang.currentLang()));
    } finally {
      this.deleting.set(false);
    }
  }

  protected async copyToLibrary(uuid: string): Promise<void> {
    this.copying.set(true);
    try {
      await this.devicesService.copyToLibrary(uuid);
      this.packsService.refresh();
      this.snackbar.success(translate('Copied to library', this.lang.currentLang()));
    } catch (err) {
      const body = err instanceof HttpErrorResponse ? err.error : null;
      const code = typeof body?.error === 'string' ? body.error : null;
      const detail = typeof body?.message === 'string' ? body.message : null;
      // File already on disk — server re-syncs; refresh UI so orphaned packs reappear.
      if (code === 'PACK_ALREADY_IN_LIBRARY') {
        this.packsService.refresh();
        this.snackbar.success(
          translate('Pack already in library — refreshing', this.lang.currentLang()),
        );
      } else {
        this.snackbar.error(
          detail ||
            (code && code !== 'ERROR' ? code : null) ||
            translate('Failed to copy to library', this.lang.currentLang()),
        );
      }
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

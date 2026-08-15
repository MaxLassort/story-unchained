import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DevicesService } from '../../../core/services/devices.service';
import { SseService } from '../../../core/services/sse.service';
import type { DevicePack } from '../../../core/models';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-devices-page',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatProgressSpinnerModule,
    MatTabsModule,
    MatTooltipModule,
  ],
  templateUrl: './devices-page.component.html',
  styleUrl: './devices-page.component.scss',
})
export class DevicesPageComponent {
  private readonly router = inject(Router);
  private readonly devicesService = inject(DevicesService);
  private readonly sseService = inject(SseService);

  protected readonly snapshots = this.devicesService.snapshots;
  protected readonly loading = this.devicesService.loadingSnapshots;
  protected readonly selectedIndex = signal(0);
  protected readonly pluggedUuid = computed(() => {
    const info = this.sseService.deviceEvent().device;
    return info.plugged ? info.uuid : null;
  });

  constructor() {
    void this.devicesService.refreshSnapshots();
  }

  protected goBack(): void {
    void this.router.navigate(['/packs']);
  }

  protected isPluggedUuid(uuid: string): boolean {
    return this.pluggedUuid() === uuid;
  }

  protected shortUuid(uuid: string): string {
    if (uuid.length <= 12) return uuid;
    return `${uuid.slice(0, 8)}…`;
  }

  protected thumbnailUrl(pack: DevicePack): string {
    if (pack.thumbnail) return pack.thumbnail;
    return `${environment.apiUrl}/packs/${pack.uuid}/thumbnail`;
  }

  protected formatSize(bytes: number): string {
    if (bytes < 1_048_576) return `${(bytes / 1024).toFixed(0)} KB`;
    if (bytes < 1_073_741_824) return `${(bytes / 1_048_576).toFixed(1)} MB`;
    return `${(bytes / 1_073_741_824).toFixed(2)} GB`;
  }

  protected formatDate(epochMs: number): string {
    return new Date(epochMs).toLocaleString();
  }
}

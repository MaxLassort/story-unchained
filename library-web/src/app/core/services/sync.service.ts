import { Injectable, NgZone, inject, signal } from '@angular/core';
import type { SyncStatusEvent } from '../models';
import { PacksService } from './packs.service';
import { SnackbarService } from './snackbar.service';
import { environment } from '../../../environments/environment';

export interface StartSyncOptions {
  silent?: boolean;
}

const TERMINAL_STATUSES: readonly SyncStatusEvent['status'][] = ['DONE', 'FAILED'];

@Injectable({ providedIn: 'root' })
export class SyncService {
  private readonly zone = inject(NgZone);
  private readonly packsService = inject(PacksService);
  private readonly snackbar = inject(SnackbarService);

  readonly syncing = signal(false);

  private eventSource: EventSource | null = null;
  private hasCurrentSyncStarted = false;

  async startSync(options: StartSyncOptions = {}): Promise<void> {
    if (this.syncing()) return;
    this.syncing.set(true);
    this.hasCurrentSyncStarted = false;

    this.zone.runOutsideAngular(() => {
      this.eventSource = new EventSource(`${environment.apiUrl}/packs/sync/events`);
      this.eventSource.onmessage = (event) => {
        try {
          const data: SyncStatusEvent = JSON.parse(event.data);
          this.zone.run(() => this.handleEvent(data, options));
        } catch {
          // ignore malformed events
        }
      };
      this.eventSource.onerror = () => {
        // EventSource will auto-reconnect; treat as transient.
      };
    });

    try {
      await this.packsService.sync();
    } catch {
      this.closeSyncStream();
      if (!options.silent) {
        this.snackbar.error('Failed to start synchronization');
      }
      this.syncing.set(false);
      return;
    }
  }

  private handleEvent(event: SyncStatusEvent, options: StartSyncOptions = {}): void {
    if (event.status === 'PENDING') {
      this.hasCurrentSyncStarted = true;
      return;
    }

    if (event.status === 'RUNNING') {
      this.hasCurrentSyncStarted = true;
      return;
    }

    if (TERMINAL_STATUSES.includes(event.status) && this.hasCurrentSyncStarted) {
      this.notifyResult(event, options);
      this.packsService.refresh();
      this.closeSyncStream();
      this.syncing.set(false);
    }
  }

  private notifyResult(event: SyncStatusEvent, options: StartSyncOptions): void {
    if (event.status === 'FAILED') {
      if (!options.silent) {
        this.snackbar.error(event.message ?? 'Synchronization failed');
      }
      return;
    }

    const synchronizedCount = event.synchronizedCount ?? 0;
    const invalidQueuedCount = event.invalidQueuedCount ?? 0;
    const failedCount = event.failedCount ?? 0;
    const summary = [
      `${synchronizedCount} synchronized`,
      `${invalidQueuedCount} invalid`,
      `${failedCount} failed`,
    ].join(', ');
    this.snackbar.success(`Sync complete: ${summary}`);
  }

  private closeSyncStream(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
}

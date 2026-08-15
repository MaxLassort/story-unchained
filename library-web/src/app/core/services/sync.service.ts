import { Injectable, inject, signal } from '@angular/core';
import type { SyncJobStatusResponse } from '../models';
import { PacksService } from './packs.service';
import { SnackbarService } from './snackbar.service';

export interface StartSyncOptions {
  silent?: boolean;
}

const POLL_INTERVAL_MS = 1000;
const TERMINAL_STATUSES = ['DONE', 'FAILED'];

@Injectable({ providedIn: 'root' })
export class SyncService {
  private readonly packsService = inject(PacksService);
  private readonly snackbar = inject(SnackbarService);

  readonly syncing = signal(false);

  async startSync(options: StartSyncOptions = {}): Promise<void> {
    if (this.syncing()) return;
    this.syncing.set(true);
    try {
      const res = await this.packsService.sync();
      const status = await this.pollUntilDone(res.jobId);
      this.packsService.refresh();
      this.notifyResult(status, options);
    } catch {
      if (!options.silent) {
        this.snackbar.error('Failed to start synchronization');
      }
    } finally {
      this.syncing.set(false);
    }
  }

  private async pollUntilDone(jobId: number): Promise<SyncJobStatusResponse> {
    for (;;) {
      const status = await this.packsService.getSyncStatus(jobId);
      if (TERMINAL_STATUSES.includes(status.status)) {
        return status;
      }
      await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
    }
  }

  private notifyResult(status: SyncJobStatusResponse, options: StartSyncOptions): void {
    if (status.status === 'FAILED') {
      if (!options.silent) {
        this.snackbar.error(status.message ?? 'Synchronization failed');
      }
      return;
    }
    const summary = [
      `${status.synchronizedCount} synchronized`,
      `${status.invalidQueuedCount} invalid`,
      `${status.failedCount} failed`,
    ].join(', ');
    this.snackbar.success(`Sync complete: ${summary}`);
  }
}

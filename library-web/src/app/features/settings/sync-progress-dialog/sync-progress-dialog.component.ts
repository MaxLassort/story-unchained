import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PacksService } from '../../../core/services/packs.service';
import type { SyncJobStatusResponse } from '../../../core/models';

interface DialogData {
  jobId: number;
}

type JobStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';

const POLL_INTERVAL_MS = 1000;
const TERMINAL_STATUSES: JobStatus[] = ['DONE', 'FAILED'];

@Component({
  selector: 'app-sync-progress-dialog',
  imports: [MatButtonModule, MatDialogModule, MatIconModule, MatProgressBarModule, MatProgressSpinnerModule],
  templateUrl: './sync-progress-dialog.component.html',
  styleUrl: './sync-progress-dialog.component.scss',
})
export class SyncProgressDialogComponent implements OnDestroy {
  protected readonly data = inject<DialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<SyncProgressDialogComponent>>(MatDialogRef);
  private readonly packsService = inject(PacksService);

  protected readonly status = signal<SyncJobStatusResponse | null>(null);
  protected readonly polling = signal(true);
  private pollTimer: ReturnType<typeof setInterval> | null = null;

  protected readonly total = computed(() => this.status()?.totalEntries ?? 0);
  protected readonly processed = computed(() => this.status()?.processedEntries ?? 0);
  protected readonly progressPercent = computed(() => {
    const t = this.total();
    if (t === 0) return 0;
    return Math.min(100, Math.round((this.processed() / t) * 100));
  });

  protected readonly currentStatus = computed<JobStatus>(() => (this.status()?.status as JobStatus) ?? 'PENDING');
  protected readonly isRunning = computed(() => {
    const s = this.currentStatus();
    return this.polling() && (s === 'PENDING' || s === 'RUNNING');
  });
  protected readonly isFailed = computed(() => this.currentStatus() === 'FAILED');

  protected readonly statusLabel = computed(() => {
    switch (this.currentStatus()) {
      case 'PENDING':
        return 'Pending';
      case 'RUNNING':
        return 'Running';
      case 'DONE':
        return 'Done';
      case 'FAILED':
        return 'Failed';
      default:
        return 'Unknown';
    }
  });

  protected readonly statusClass = computed(() => `sync-progress__status--${this.currentStatus().toLowerCase()}`);

  constructor() {
    this.startPolling();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  private startPolling(): void {
    void this.fetchOnce();
    this.pollTimer = setInterval(() => void this.fetchOnce(), POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    this.polling.set(false);
    if (this.pollTimer != null) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private async fetchOnce(): Promise<void> {
    if (!this.polling()) return;
    try {
      const res = await this.packsService.getSyncStatus(this.data.jobId);
      this.status.set(res);
      if (TERMINAL_STATUSES.includes(res.status as JobStatus)) {
        this.stopPolling();
        if (res.status === 'DONE') {
          this.packsService.refresh();
        }
      }
    } catch {
      this.stopPolling();
      this.status.update((current) =>
        current
          ? { ...current, status: 'FAILED', message: 'Lost connection to server while polling job status.' }
          : current,
      );
    }
  }

  protected close(): void {
    this.dialogRef.close(this.status());
  }
}

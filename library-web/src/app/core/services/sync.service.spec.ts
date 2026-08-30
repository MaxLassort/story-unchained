import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { PacksService } from './packs.service';
import { SnackbarService } from './snackbar.service';
import { SyncService } from './sync.service';
import type { SyncStatusEvent } from '../models';

function event(status: SyncStatusEvent['status'], extra: Partial<SyncStatusEvent> = {}): SyncStatusEvent {
  return { status, ...extra };
}

class FakeEventSource {
  static latest: FakeEventSource | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: (() => void) | null = null;
  readonly url: string;
  closed = false;

  constructor(url: string) {
    this.url = url;
    FakeEventSource.latest = this;
  }

  emit(data: SyncStatusEvent): void {
    this.onmessage?.({ data: JSON.stringify(data) } as MessageEvent);
  }

  close(): void {
    this.closed = true;
  }
}

describe('SyncService', () => {
  let service: SyncService;
  let packsService: { value: { sync: ReturnType<typeof vi.fn>; refresh: ReturnType<typeof vi.fn> } };
  let snackbar: { value: SnackbarService; success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    vi.useFakeTimers();
    FakeEventSource.latest = null;
    (globalThis as unknown as { EventSource: typeof FakeEventSource }).EventSource = FakeEventSource;
    packsService = { value: { sync: vi.fn().mockResolvedValue(undefined), refresh: vi.fn() } };
    snackbar = createSnackbar();

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        { provide: PacksService, useValue: packsService.value },
        { provide: SnackbarService, useValue: snackbar.value },
        SyncService,
      ],
    });
    service = TestBed.inject(SyncService);
  });

  afterEach(() => {
    vi.useRealTimers();
    FakeEventSource.latest = null;
  });

  it('opens SSE before POST and closes on DONE', async () => {
    const start = service.startSync();
    await Promise.resolve();
    const source = FakeEventSource.latest!;

    expect(source.url).toContain('/packs/sync/events');
    expect(packsService.value.sync).toHaveBeenCalledTimes(1);

    source.emit(event('PENDING'));
    source.emit(event('RUNNING'));
    source.emit(event('DONE', { synchronizedCount: 3, invalidQueuedCount: 1, failedCount: 0 }));
    await start;

    expect(source.closed).toBe(true);
    expect(service.syncing()).toBe(false);
    expect(packsService.value.refresh).toHaveBeenCalledTimes(1);
    expect(snackbar.success).toHaveBeenCalledWith('Sync complete: 3 synchronized, 1 invalid, 0 failed');
  });

  it('closes on FAILED and refreshes packs', async () => {
    const start = service.startSync();
    await Promise.resolve();
    const source = FakeEventSource.latest!;

    source.emit(event('PENDING'));
    source.emit(event('FAILED', { message: 'Disk error' }));
    await start;

    expect(source.closed).toBe(true);
    expect(service.syncing()).toBe(false);
    expect(packsService.value.refresh).toHaveBeenCalledTimes(1);
    expect(snackbar.error).toHaveBeenCalledWith('Disk error');
  });

  it('ignores stale terminal replay before the current lifecycle', async () => {
    const start = service.startSync();
    await Promise.resolve();
    const source = FakeEventSource.latest!;

    source.emit(event('DONE', { synchronizedCount: 99 }));
    expect(service.syncing()).toBe(true);

    source.emit(event('PENDING'));
    source.emit(event('DONE', { synchronizedCount: 2 }));
    await start;

    expect(service.syncing()).toBe(false);
    expect(packsService.value.refresh).toHaveBeenCalledTimes(1);
  });

  it('does not start a second sync while one is active', async () => {
    const first = service.startSync();
    await Promise.resolve();
    service.startSync();
    expect(packsService.value.sync).toHaveBeenCalledTimes(1);

    FakeEventSource.latest!.emit(event('PENDING'));
    FakeEventSource.latest!.emit(event('DONE'));
    await first;
  });

  it('handles a failed POST without an unhandled rejection', async () => {
    packsService.value.sync.mockRejectedValueOnce(new Error('Network'));

    await service.startSync();

    expect(service.syncing()).toBe(false);
    expect(snackbar.error).toHaveBeenCalledWith('Failed to start synchronization');
  });
});

function createSnackbar() {
  const success = vi.fn();
  const error = vi.fn();
  return {
    value: { success, error } as unknown as SnackbarService,
    success,
    error,
  };
}

import { Injectable, NgZone, computed, inject, signal } from '@angular/core';
import type { DeviceEvent } from '../models';
import { environment } from '../../../environments/environment';

const EMPTY_EVENT: DeviceEvent = {
  device: { plugged: false, uuid: null, serial: null, firmware: null, driver: null, storage: null, error: false },
  packs: null,
  conversion: null,
};

@Injectable({ providedIn: 'root' })
export class SseService {
  private readonly zone = inject(NgZone);
  private readonly url = `${environment.apiUrl}/devices/events`;

  readonly deviceEvent = signal<DeviceEvent>(EMPTY_EVENT);
  readonly isPlugged = computed(() => this.deviceEvent().device.plugged);

  private eventSource: EventSource | null = null;

  connect(): void {
    if (this.eventSource) return;

    this.zone.runOutsideAngular(() => {
      this.eventSource = new EventSource(this.url);
      this.eventSource.onmessage = (event) => {
        try {
          const data: DeviceEvent = JSON.parse(event.data);
          this.zone.run(() => {
            this.deviceEvent.set(data);
          });
        } catch {
          // ignore malformed events
        }
      };
      this.eventSource.onerror = () => {
        // EventSource will auto-reconnect
      };
    });
  }

  disconnect(): void {
    this.eventSource?.close();
    this.eventSource = null;
    this.deviceEvent.set(EMPTY_EVENT);
  }
}

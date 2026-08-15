import { Injectable, signal } from '@angular/core';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class HealthService {
  readonly connected = signal(false);

  private readonly backendUrl = environment.apiUrl;

  constructor() {
    void this.poll();
  }

  private async poll(): Promise<void> {
    const ok = await this.check();
    if (!ok) {
      setTimeout(() => this.poll(), 5000);
    }
  }

  private async check(): Promise<boolean> {
    try {
      const response = await fetch(`${this.backendUrl}/settings`);
      const ok = response.ok;
      this.connected.set(ok);
      return ok;
    } catch {
      this.connected.set(false);
      return false;
    }
  }
}

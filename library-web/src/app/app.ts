import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HealthService } from './core/services/health.service';
import { SyncService } from './core/services/sync.service';
import { AppHeaderComponent } from './shared/components/app-header/app-header.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, AppHeaderComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  private readonly health = inject(HealthService);
  private readonly syncService = inject(SyncService);
  readonly connected = this.health.connected;

  private startupSyncTriggered = false;

  constructor() {
    effect(() => {
      if (this.connected() && !this.startupSyncTriggered) {
        this.startupSyncTriggered = true;
        void this.syncService.startSync({ silent: true });
      }
    });
  }
}

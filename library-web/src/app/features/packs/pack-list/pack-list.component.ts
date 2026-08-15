import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatTooltipModule } from '@angular/material/tooltip';
import { lastValueFrom } from 'rxjs';
import { PacksService } from '../../../core/services/packs.service';
import { SseService } from '../../../core/services/sse.service';
import { MetadataService } from '../../../core/services/metadata.service';
import { SnackbarService } from '../../../core/services/snackbar.service';
import { PackFiltersComponent } from '../components/pack-filters/pack-filters.component';
import { PackCardComponent } from '../components/pack-card/pack-card.component';
import { PaginationBarComponent } from '../components/pagination-bar/pagination-bar.component';
import { DevicePanelComponent } from '../../devices/device-panel/device-panel.component';

@Component({
  selector: 'app-pack-list',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSidenavModule,
    MatTooltipModule,
    PackFiltersComponent,
    PackCardComponent,
    PaginationBarComponent,
    DevicePanelComponent,
  ],
  templateUrl: './pack-list.component.html',
  styleUrl: './pack-list.component.scss',
})
export class PackListComponent {
  private readonly router = inject(Router);
  private readonly packsService = inject(PacksService);
  private readonly sseService = inject(SseService);
  private readonly metadataService = inject(MetadataService);
  private readonly snackbar = inject(SnackbarService);
  private readonly sidenav = viewChild(MatSidenav);

  readonly packs = this.packsService.packs;
  readonly loading = this.packsService.loading;
  readonly total = this.packsService.total;
  readonly totalPages = this.packsService.totalPages;
  readonly page = this.packsService.page;
  readonly pageSize = this.packsService.pageSize;
  protected readonly isPlugged = this.sseService.isPlugged;
  protected readonly metadataRefreshing = this.metadataService.refreshing;

  constructor() {
  }

  readonly showOfficial = this.packsService.showOfficial;
  readonly showUnavailable = this.packsService.showUnavailable;
  readonly showFrFr = this.packsService.showFrFr;
  readonly sortOrder = signal<'asc' | 'desc'>('asc');

  readonly sortedPacks = computed(() => {
    const order = this.sortOrder();
    return [...this.packs()].sort((a, b) => {
      const ta = (a.metadata.title ?? '').toLowerCase();
      const tb = (b.metadata.title ?? '').toLowerCase();
      return order === 'asc' ? ta.localeCompare(tb) : tb.localeCompare(ta);
    });
  });

  protected toggleSidenav(): void {
    this.sidenav()?.toggle();
  }
  

  setPage(page: number): void {
    this.page.set(page);
  }

  setPageSize(size: number): void {
    this.pageSize.set(size);
    this.page.set(0);
  }

  protected async refreshMetadata(): Promise<void> {
    try {
      const res = await this.metadataService.refresh();
      this.snackbar.success(res.message ?? 'Metadata refreshed');
      this.packsService.refresh();
    } catch {
      // snackbar handled by interceptor
    }
  }
}

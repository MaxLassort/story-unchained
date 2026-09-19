import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { Pack, StoryDraftSummary } from '../../../core/models';
import { PacksService } from '../../../core/services/packs.service';
import { StoryDraftService } from '../../../core/services/story-draft.service';
import { SseService } from '../../../core/services/sse.service';
import { MetadataService } from '../../../core/services/metadata.service';
import { SnackbarService } from '../../../core/services/snackbar.service';
import { LanguageService } from '../../../core/services/language.service';
import { PackFiltersComponent } from '../components/pack-filters/pack-filters.component';
import { PackCardComponent } from '../components/pack-card/pack-card.component';
import { PaginationBarComponent } from '../components/pagination-bar/pagination-bar.component';
import { DevicePanelComponent } from '../../devices/device-panel/device-panel.component';
import { TranslatePipe, translate } from '../../../core/pipes/translate.pipe';

/** Maps a story draft to a pack-shaped card for the library grid. */
function draftToPackCard(draft: StoryDraftSummary, thumbnailUrl: string | null): Pack {
  return {
    id: draft.id,
    sourcePackId: draft.sourcePackId ?? null,
    metadata: {
      title: draft.title?.trim() || 'Untitled draft',
      description: draft.description,
      thumbnail: thumbnailUrl,
      version: 0,
      factoryDisabled: false,
      nightModeAvailable: false,
      official: false,
      linkedOfficialPackId: null,
      locale: null,
      ageMin: null,
      ageMax: null,
      durationMs: null,
      storyCount: draft.chapters.length || null,
      slug: null,
      unchained: true,
      draft: true,
    },
    variants: [],
  };
}

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
    TranslatePipe,
  ],
  templateUrl: './pack-list.component.html',
  styleUrl: './pack-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PackListComponent implements OnInit {
  private readonly packsService = inject(PacksService);
  private readonly draftsService = inject(StoryDraftService);
  private readonly sseService = inject(SseService);
  private readonly metadataService = inject(MetadataService);
  private readonly snackbar = inject(SnackbarService);
  private readonly lang = inject(LanguageService);
  private readonly sidenav = viewChild(MatSidenav);

  readonly packs = this.packsService.packs;
  readonly loading = this.packsService.loading;
  readonly total = this.packsService.total;
  readonly totalPages = this.packsService.totalPages;
  readonly page = this.packsService.page;
  readonly pageSize = this.packsService.pageSize;
  protected readonly isPlugged = this.sseService.isPlugged;
  protected readonly metadataRefreshing = this.metadataService.refreshing;

  /** In-progress story drafts shown as temporary pack cards. */
  readonly draftPacks = signal<Pack[]>([]);

  readonly showOfficial = this.packsService.showOfficial;
  readonly showUnchainedOnly = this.packsService.showUnchainedOnly;
  readonly showCurrentLocale = this.packsService.showCurrentLocale;
  readonly showDrafts = signal(true);
  readonly ageMin = this.packsService.ageMin;
  readonly ageMax = this.packsService.ageMax;
  readonly officialMode = this.packsService.officialMode;
  readonly sortOrder = signal<'asc' | 'desc'>('asc');

  readonly sortedPacks = computed(() => {
    if (this.officialMode()) {
      return this.sortPacks(this.packs());
    }
    const library = this.sortPacks(this.packs());
    // Drafts always first when visible.
    return this.showDrafts() ? [...this.draftPacks(), ...library] : library;
  });

  ngOnInit(): void {
    void this.loadDrafts();
  }

  protected toggleSidenav(): void {
    this.sidenav()?.toggle();
  }

  setPage(page: number): void {
    this.page.set(page);
  }

  setOfficialMode(on: boolean): void {
    this.officialMode.set(on);
    this.page.set(0);
  }

  setPageSize(size: number): void {
    this.pageSize.set(size);
    this.page.set(0);
  }

  protected async refreshMetadata(): Promise<void> {
    try {
      const res = await this.metadataService.refresh();
      this.snackbar.success(res.message ?? translate('Metadata refreshed', this.lang.currentLang()));
      this.packsService.refresh();
      void this.loadDrafts();
    } catch {
      // snackbar handled by interceptor
    }
  }

  /** Called by draft cards after delete so the grid refreshes. */
  protected onDraftDeleted(draftId: string): void {
    this.draftPacks.update((list) => list.filter((p) => p.id !== draftId));
  }

  private async loadDrafts(): Promise<void> {
    try {
      const drafts = await this.draftsService.listDrafts();
      this.draftPacks.set(
        drafts.map((d) =>
          draftToPackCard(
            d,
            d.hasThumbnail ? this.draftsService.draftThumbnailUrl(d.id) : null,
          ),
        ),
      );
    } catch {
      this.draftPacks.set([]);
    }
  }

  private sortPacks(packs: Pack[]): Pack[] {
    const order = this.sortOrder();
    return [...packs].sort((a, b) => {
      const ta = (a.metadata.title ?? '').toLowerCase();
      const tb = (b.metadata.title ?? '').toLowerCase();
      return order === 'asc' ? ta.localeCompare(tb) : tb.localeCompare(ta);
    });
  }
}

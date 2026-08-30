import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { CdkCopyToClipboard } from '@angular/cdk/clipboard';
import { form, FormField, submit } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatOptionModule } from '@angular/material/core';
import { MatSelectModule } from '@angular/material/select';
import type { Pack } from '../../../core/models';
import { PacksService } from '../../../core/services/packs.service';
import { SnackbarService } from '../../../core/services/snackbar.service';
import { FormatBadgeComponent } from '../components/format-badge/format-badge.component';
import { LoadingOverlayComponent } from '../../../shared/components/loading-overlay/loading-overlay.component';

const LOCALES = [
  { value: 'fr_FR', label: 'Français (FR)' },
  { value: 'fr_CA', label: 'Français (CA)' },
  { value: 'en_US', label: 'English (US)' },
  { value: 'en_GB', label: 'English (GB)' },
  { value: 'de_DE', label: 'Deutsch' },
  { value: 'es_ES', label: 'Español (ES)' },
  { value: 'es_MX', label: 'Español (MX)' },
  { value: 'it_IT', label: 'Italiano' },
  { value: 'nl_NL', label: 'Nederlands (NL)' },
  { value: 'nl_BE', label: 'Nederlands (BE)' },
  { value: 'ru_RU', label: 'Русский' },
];

@Component({
  selector: 'app-pack-detail',
  imports: [CdkCopyToClipboard, FormField, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatOptionModule, MatSelectModule, RouterModule, FormatBadgeComponent, LoadingOverlayComponent],
  templateUrl: './pack-detail.component.html',
  styleUrl: './pack-detail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PackDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly packsService = inject(PacksService);
  private readonly snackbar = inject(SnackbarService);

  protected readonly pack = signal<Pack | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly busyLabel = signal('');
  protected readonly locales = LOCALES;

  protected readonly metadataModel = signal({
    title: '',
    description: '',
    locale: '',
    ageMin: 0,
    ageMax: 0,
    durationMs: 0,
    storyCount: 0,
  });
  protected readonly metadataForm = form(this.metadataModel);

  protected readonly isOfficial = computed(() => this.pack()?.metadata.official ?? false);

  protected readonly thumbnailUrl = computed(() => this.pack()?.metadata.thumbnail ?? '');

  constructor() {
    void this.loadPack();
  }

  private async loadPack(): Promise<void> {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) return;
    try {
      const packs = await this.packsService.getAllPacks();
      const found = packs.find((p) => p.id === id);
      if (found) {
        this.pack.set(found);
        this.metadataModel.set({
          title: found.metadata.title ?? '',
          description: found.metadata.description ?? '',
          locale: found.metadata.locale ?? '',
          ageMin: found.metadata.ageMin ?? 0,
          ageMax: found.metadata.ageMax ?? 0,
          durationMs: found.metadata.durationMs ? Math.round(found.metadata.durationMs / 60000) : 0,
          storyCount: found.metadata.storyCount ?? 0,
        });
      }
    } catch {
      this.snackbar.error('Failed to load pack');
    } finally {
      this.loading.set(false);
    }
  }

  protected onThumbnailClick(): void {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = async () => {
      const file = input.files?.[0];
      if (!file || !this.pack()) return;
      try {
        this.busyLabel.set('Uploading thumbnail...');
        this.busy.set(true);
        await this.packsService.uploadThumbnail(this.pack()!.id, file);
        await this.loadPack();
        this.snackbar.success('Thumbnail updated');
      } catch {
        this.snackbar.error('Failed to upload thumbnail');
      } finally {
        this.busy.set(false);
      }
    };
    input.click();
  }

  protected onSave(): void {
    void submit(this.metadataForm, async () => {
      const p = this.pack();
      if (!p) return;
      try {
        this.busyLabel.set('Saving...');
        this.busy.set(true);
        const m = this.metadataModel();
        await this.packsService.updateMetadata(p.id, {
          title: m.title || null,
          description: m.description || null,
          locale: m.locale || null,
          ageMin: m.ageMin || null,
          ageMax: m.ageMax || null,
          durationMs: m.durationMs ? m.durationMs * 60000 : null,
          storyCount: m.storyCount || null,
        });
        this.snackbar.success('Metadata updated');
        void this.router.navigate(['/packs']);
      } catch {
        this.snackbar.error('Failed to update');
      } finally {
        this.busy.set(false);
      }
    });
  }
}

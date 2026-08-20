import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PacksService } from '../../../core/services/packs.service';
import { SettingsService } from '../../../core/services/settings.service';
import { SnackbarService } from '../../../core/services/snackbar.service';
import { SettingsDialogComponent } from '../../../features/settings/settings-dialog/settings-dialog.component';
import { CreateStoryButtonComponent } from '../create-story-button/create-story-button.component';

@Component({
  selector: 'app-header',
  imports: [RouterLink, RouterLinkActive, MatButtonModule, MatIconModule, MatTooltipModule, CreateStoryButtonComponent],
  templateUrl: './app-header.component.html',
  styleUrl: './app-header.component.scss',
})
export class AppHeaderComponent {
  private readonly dialog = inject(MatDialog);
  private readonly packsService = inject(PacksService);
  private readonly settingsService = inject(SettingsService);
  private readonly snackbar = inject(SnackbarService);

  protected readonly searchTerm = this.packsService.searchTerm;

  protected updateSearch(value: string): void {
    this.searchTerm.set(value);
    this.packsService.page.set(0);
  }

  protected async openSettings(): Promise<void> {
    await this.settingsService.load();
    const ref = this.dialog.open(SettingsDialogComponent, {
      data: { settings: this.settingsService.settings() },
      width: '640px',
    });
    ref.afterClosed().subscribe(async (result) => {
      if (!result) return;
      try {
        await this.settingsService.save(result);
        this.snackbar.success('Settings saved');
        this.packsService.refresh();
      } catch {
        this.snackbar.error('Failed to save settings');
      }
    });
  }
}

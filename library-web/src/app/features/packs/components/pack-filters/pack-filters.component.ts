import { Component, model } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-pack-filters',
  imports: [MatButtonModule, MatChipsModule, MatIconModule],
  templateUrl: './pack-filters.component.html',
  styleUrl: './pack-filters.component.scss',
})
export class PackFiltersComponent {
  readonly showOfficial = model(true);
  readonly showUnavailable = model(false);
  readonly showFrFr = model(true);
  readonly sortOrder = model<'asc' | 'desc'>('asc');

  toggleSort(): void {
    this.sortOrder.update((v) => (v === 'asc' ? 'desc' : 'asc'));
  }
}

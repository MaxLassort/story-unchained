import { Component, computed, model } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatSliderModule } from '@angular/material/slider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TranslatePipe } from '../../../../core/pipes/translate.pipe';

@Component({
  selector: 'app-pack-filters',
  imports: [MatButtonModule, MatChipsModule, MatIconModule, MatSliderModule, MatTooltipModule, TranslatePipe],
  templateUrl: './pack-filters.component.html',
  styleUrl: './pack-filters.component.scss',
})
export class PackFiltersComponent {
  readonly showOfficial = model(true);
  readonly showCurrentLocale = model(true);
  readonly sortOrder = model<'asc' | 'desc'>('asc');
  readonly ageMin = model<number | null>(null);
  readonly ageMax = model<number | null>(null);

  readonly AGE_MIN = 1;
  readonly AGE_MAX = 15;

  readonly hasAgeFilter = computed(() => this.ageMin() !== null || this.ageMax() !== null);

  setAgeMin(value: number): void {
    this.ageMin.set(Math.min(value, this.ageMax() ?? this.AGE_MAX));
  }

  setAgeMax(value: number): void {
    this.ageMax.set(Math.max(value, this.ageMin() ?? this.AGE_MIN));
  }

  clearAgeFilter(): void {
    this.ageMin.set(null);
    this.ageMax.set(null);
  }

  toggleSort(): void {
    this.sortOrder.update((v) => (v === 'asc' ? 'desc' : 'asc'));
  }
}

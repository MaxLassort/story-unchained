import { Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';

const PAGE_SIZES = [12, 24, 48, 96];

@Component({
  selector: 'app-pagination-bar',
  imports: [MatButtonModule, MatIconModule, MatSelectModule],
  templateUrl: './pagination-bar.component.html',
  styleUrl: './pagination-bar.component.scss',
})
export class PaginationBarComponent {
  readonly page = input(0);
  readonly pageSize = input(24);
  readonly total = input(0);
  readonly totalPages = input(1);

  readonly pageChange = output<number>();
  readonly pageSizeChange = output<number>();

  protected readonly pageSizes = PAGE_SIZES;

  protected readonly isFirstPage = computed(() => this.page() === 0);
  protected readonly isLastPage = computed(() => this.page() >= this.totalPages() - 1);

  protected readonly range = computed(() => {
    const start = this.total() === 0 ? 0 : this.page() * this.pageSize() + 1;
    const end = Math.min((this.page() + 1) * this.pageSize(), this.total());
    return `${start}-${end} of ${this.total()}`;
  });

  protected onPageSizeChange(size: number): void {
    this.pageSizeChange.emit(size);
  }

  prev(): void {
    if (!this.isFirstPage()) this.pageChange.emit(this.page() - 1);
  }
  next(): void {
    if (!this.isLastPage()) this.pageChange.emit(this.page() + 1);
  }
}

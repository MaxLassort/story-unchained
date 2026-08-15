import { Component, input } from '@angular/core';
import type { PackFormat } from '../../../../core/models';

const FORMAT_LABELS: Record<PackFormat, string> = {
  ARCHIVE: 'Archive',
  RAW: 'Binary',
  FS: 'Folder',
  UNKNOWN: 'Unknown',
};

@Component({
  selector: 'app-format-badge',
  templateUrl: './format-badge.component.html',
  styleUrl: './format-badge.component.scss',
})
export class FormatBadgeComponent {
  readonly format = input<PackFormat | null>();
  protected get label(): string {
    const fmt = this.format();
    return fmt ? FORMAT_LABELS[fmt] : '';
  }
}

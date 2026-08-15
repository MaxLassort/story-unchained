import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import type { PackFormat, PackConversionRequest } from '../../../../core/models';

const FORMATS: { label: string; value: PackFormat }[] = [
  { label: 'Archive (.zip)', value: 'ARCHIVE' },
  { label: 'Binary (.pack)', value: 'RAW' },
  { label: 'Folder', value: 'FS' },
];

@Component({
  selector: 'app-pack-convert-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatSelectModule, MatFormFieldModule],
  template: `
    <h2 mat-dialog-title>Convert Pack</h2>
    <mat-dialog-content>
      <p><strong>{{ data.title }}</strong></p>
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Source format</mat-label>
        <mat-select [(ngModel)]="source">
          @for (fmt of formats; track fmt.value) {
            <mat-option [value]="fmt.value">{{ fmt.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Target format</mat-label>
        <mat-select [(ngModel)]="target">
          @for (fmt of formats; track fmt.value) {
            <mat-option [value]="fmt.value" [disabled]="fmt.value === source()">{{ fmt.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="primary" [disabled]="source() === target()" (click)="confirm()">Convert</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-form-field {
      width: 100%;
      margin-bottom: 0.5rem;
    }
  `,
})
export class PackConvertDialogComponent {
  protected readonly data: { title: string; id: string } = inject(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<PackConvertDialogComponent, PackConversionRequest>>(MatDialogRef);

  protected readonly formats = FORMATS;
  protected readonly source = signal<PackFormat>('ARCHIVE');
  protected readonly target = signal<PackFormat>('FS');

  protected confirm(): void {
    this.dialogRef.close({ sourceFormat: this.source(), targetFormat: this.target() });
  }
}

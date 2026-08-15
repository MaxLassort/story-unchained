import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';

@Component({
  selector: 'app-pack-delete-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>Delete Pack</h2>
    <mat-dialog-content>
      <p>Are you sure you want to delete <strong>{{ data.title }}</strong>?</p>
      <p class="pack-delete-dialog__warning">This action cannot be undone. The pack files will remain on disk.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="warn" [mat-dialog-close]="true">Delete</button>
    </mat-dialog-actions>
  `,
  styles: `
    .pack-delete-dialog__warning {
      color: var(--mat-sys-error);
      font-size: 0.85rem;
    }
  `,
})
export class PackDeleteDialogComponent {
  protected readonly data: { title: string; id: string } = inject(MAT_DIALOG_DATA);
}

import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';

@Component({
  selector: 'app-copy-pack-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>Copyright Notice</h2>
    <mat-dialog-content>
      <p>This pack <strong>{{ data.title }}</strong> is an official Lunii story pack.</p>
      <p>Copying official packs may violate copyright. Are you sure you want to proceed?</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="warn" [mat-dialog-close]="true">Copy Anyway</button>
    </mat-dialog-actions>
  `,
})
export class CopyPackDialogComponent {
  protected readonly data: { title: string; id: string } = inject(MAT_DIALOG_DATA);
}

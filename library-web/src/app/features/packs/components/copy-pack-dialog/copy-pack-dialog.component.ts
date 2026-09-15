import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { TranslatePipe } from '../../../../core/pipes/translate.pipe';

@Component({
  selector: 'app-copy-pack-dialog',
  imports: [MatButtonModule, MatDialogModule, TranslatePipe],
  template: `
    <h2 mat-dialog-title>{{ 'Copyright Notice' | translate }}</h2>
    <mat-dialog-content>
      <p>{{ 'This pack' | translate }} <strong>{{ data.title }}</strong> {{ 'is an official Lunii story pack.' | translate }}</p>
      <p>{{ 'Copying official packs may violate copyright. Are you sure you want to proceed?' | translate }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>{{ 'Cancel' | translate }}</button>
      <button mat-flat-button color="warn" [mat-dialog-close]="true">{{ 'Copy Anyway' | translate }}</button>
    </mat-dialog-actions>
  `,
})
export class CopyPackDialogComponent {
  protected readonly data: { title: string; id: string } = inject(MAT_DIALOG_DATA);
}

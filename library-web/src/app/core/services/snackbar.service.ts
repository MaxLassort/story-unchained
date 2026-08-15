import { Injectable, inject } from '@angular/core';
import { MatSnackBar, MatSnackBarConfig } from '@angular/material/snack-bar';

@Injectable({ providedIn: 'root' })
export class SnackbarService {
  private readonly snackBar = inject(MatSnackBar);

  private readonly baseConfig: MatSnackBarConfig = {
    duration: 4000,
    horizontalPosition: 'end',
    verticalPosition: 'bottom',
  };

  success(message: string): void {
    this.snackBar.open(message, '✕', {
      ...this.baseConfig,
      panelClass: ['snackbar-success'],
    });
  }

  error(message: string): void {
    this.snackBar.open(message, '✕', {
      ...this.baseConfig,
      panelClass: ['snackbar-error'],
      duration: 6000,
    });
  }
}

import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { SnackbarService } from '../services/snackbar.service';
import { SKIP_ERROR_SNACKBAR } from '../services/http-context';

export const httpErrorInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.context.get(SKIP_ERROR_SNACKBAR)) {
    return next(req);
  }

  const snackbar = inject(SnackbarService);

  return next(req).pipe(
    catchError((err) => {
      if (err?.error instanceof Blob) {
        err.error
          .text()
          .then((text: string) => {
            try {
              const parsed = JSON.parse(text);
              snackbar.error(parsed?.error ?? parsed?.message ?? text);
            } catch {
              snackbar.error(text || `Erreur ${err?.status ?? 'réseau'}`);
            }
          })
          .catch(() => {
            snackbar.error(err?.message ?? `Erreur ${err?.status ?? 'réseau'}`);
          });
      } else {
        const message =
          err?.error?.error ??
          err?.error?.message ??
          err?.message ??
          `Erreur ${err?.status ?? 'réseau'}`;
        snackbar.error(message);
      }
      return throwError(() => err);
    }),
  );
};

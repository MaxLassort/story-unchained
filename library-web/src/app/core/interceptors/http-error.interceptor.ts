import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { SnackbarService } from '../services/snackbar.service';
import { LanguageService } from '../services/language.service';
import { SKIP_ERROR_SNACKBAR } from '../services/http-context';
import { translate } from '../pipes/translate.pipe';

export const httpErrorInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.context.get(SKIP_ERROR_SNACKBAR)) {
    return next(req);
  }

  const snackbar = inject(SnackbarService);
  const lang = inject(LanguageService);

  return next(req).pipe(
    catchError((err) => {
      if (err?.error instanceof Blob) {
        err.error
          .text()
          .then((text: string) => {
            try {
              const parsed = JSON.parse(text);
              const detail = parsed?.message;
              const code = parsed?.error;
              snackbar.error(
                (typeof detail === 'string' && detail.trim() ? detail : null) ??
                  (typeof code === 'string' && code.trim() && code !== 'ERROR' ? code : null) ??
                  text,
              );
            } catch {
              snackbar.error(text || translate('Network error', lang.currentLang()));
            }
          })
          .catch(() => {
            snackbar.error(err?.message ?? translate('Network error', lang.currentLang()));
          });
      } else {
        const code = err?.error?.error;
        const detail = err?.error?.message;
        // Prefer human message over opaque codes like "ERROR" / "DEVICE_NOT_PLUGGED".
        const message =
          (typeof detail === 'string' && detail.trim() ? detail : null) ??
          (typeof code === 'string' && code.trim() && code !== 'ERROR' ? code : null) ??
          err?.message ??
          translate('Network error', lang.currentLang());
        snackbar.error(message);
      }
      return throwError(() => err);
    }),
  );
};

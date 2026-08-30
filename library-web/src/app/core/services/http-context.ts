import { HttpContext, HttpContextToken } from '@angular/common/http';

export const SKIP_ERROR_SNACKBAR = new HttpContextToken<boolean>(() => false);

/**
 * HTTP context that suppresses the global error snackbar. Use for requests
 * whose errors are handled inline by the caller (e.g. the story-creation flow
 * surfaces `saveError`/`finalizeError`) or silently tolerated (e.g. missing
 * draft binaries on reload), so the user isn't shown both an inline message and
 * a redundant snackbar.
 */
export function silentHttpContext(): HttpContext {
  return new HttpContext().set(SKIP_ERROR_SNACKBAR, true);
}

import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../services/auth.service';

/**
 * Functional HTTP interceptor that attaches the JWT Bearer token to outbound
 * API requests (feat/dev-jwt-auth).
 *
 * Rules:
 *  - Only injects the header when a token is present in AuthService.
 *  - Targets requests whose URL starts with /api, EXCEPT /api/auth/login
 *    (which is the login endpoint itself — no chicken-and-egg).
 *  - All other requests (public game reads, tiles, /ws/**) pass through
 *    unmodified so anonymous spectating keeps working.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.token();

  const needsToken =
    token !== null &&
    req.url.startsWith('/api') &&
    !req.url.startsWith('/api/auth/login');

  if (!needsToken) {
    return next(req);
  }

  const authed = req.clone({
    setHeaders: { Authorization: `Bearer ${token}` },
  });
  return next(authed);
};

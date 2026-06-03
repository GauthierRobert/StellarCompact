import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Functional route guard that requires a valid (non-expired) JWT.
 *
 * If the user is not authenticated, redirects to /login with a `returnUrl`
 * query parameter so the login component can bounce back after a successful
 * sign-in.  Returns a UrlTree (not a boolean) so the Router handles the
 * redirect atomically without a double navigation.
 */
export const authGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }

  // Build the attempted URL so login can redirect back afterwards.
  const attemptedUrl = route.url.map((seg) => seg.path).join('/');
  return router.createUrlTree(['/login'], {
    queryParams: { returnUrl: attemptedUrl || '/me' },
  });
};

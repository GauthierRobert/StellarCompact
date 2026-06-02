import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'galaxy',
    pathMatch: 'full',
  },
  {
    path: 'galaxy',
    loadComponent: () =>
      import('./features/galaxy/galaxy.component').then(
        (m) => m.GalaxyComponent,
      ),
  },
  {
    path: 'config',
    loadComponent: () =>
      import('./features/config/config.component').then(
        (m) => m.ConfigComponent,
      ),
  },
  {
    path: 'spectator',
    loadComponent: () =>
      import('./features/spectator/spectator.component').then(
        (m) => m.SpectatorComponent,
      ),
  },
];

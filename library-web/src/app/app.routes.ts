import { Routes } from '@angular/router';
import { PackListComponent } from './features/packs/pack-list/pack-list.component';

export const routes: Routes = [
  { path: '', redirectTo: '/packs', pathMatch: 'full' },
  { path: 'packs', component: PackListComponent },
  { path: 'packs/:id', loadComponent: () => import('./features/packs/pack-detail/pack-detail.component').then((m) => m.PackDetailComponent) },
  { path: 'stories/new', loadComponent: () => import('./features/story-creation/story-creation-page/story-creation-page.component').then((m) => m.StoryCreationPageComponent) },
  { path: 'devices', loadComponent: () => import('./features/devices/devices-page/devices-page.component').then((m) => m.DevicesPageComponent) },
];

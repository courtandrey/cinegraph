import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./pages/search/search.component').then(m => m.SearchComponent)
  },
  {
    path: 'film/:id',
    loadComponent: () =>
      import('./pages/graph/graph.component').then(m => m.GraphComponent)
  },
  { path: '**', redirectTo: '' }
];

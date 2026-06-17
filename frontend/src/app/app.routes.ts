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
  {
    path: 'letterboxd/:hash/film/:id',
    loadComponent: () =>
      import('./pages/graph/graph.component').then(m => m.GraphComponent)
  },
  {
    path: 'letterboxd/:hash',
    loadComponent: () =>
      import('./pages/letterboxd/letterboxd-graph.component').then(m => m.LetterboxdGraphComponent)
  },
  { path: '**', redirectTo: '' }
];

import { Component, DestroyRef, EventEmitter, Output, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { catchError, debounceTime, switchMap, tap } from 'rxjs/operators';
import { of } from 'rxjs';
import { MovieApiService } from '../../../services/movie-api.service';
import { LetterboxdStore } from '../../../services/letterboxd-store.service';
import { MovieSummary } from '../../../models/movie.model';

const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';

/**
 * Modal typeahead over the uploaded film set. Films present in some graph component
 * are selectable; films in the set but connected to nothing are shown disabled.
 */
@Component({
  selector: 'app-letterboxd-search',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './letterboxd-search.component.html',
  styleUrl: './letterboxd-search.component.scss'
})
export class LetterboxdSearchComponent {
  private api = inject(MovieApiService);
  private store = inject(LetterboxdStore);
  private destroyRef = inject(DestroyRef);

  @Output() pick = new EventEmitter<number>();
  @Output() closed = new EventEmitter<void>();

  readonly searchControl = new FormControl('');
  readonly results = signal<MovieSummary[]>([]);
  readonly loading = signal(false);
  readonly activeIndex = signal(-1);

  // Every node id across all graph components — anything else is "not connected".
  private readonly connectedIds = new Set<number>(
    this.store.graphs().flatMap(g => g.nodes.map(n => n.id))
  );

  constructor() {
    this.searchControl.valueChanges.pipe(
      // Enter the loading state immediately (before the debounce) so the "no films"
      // message never flashes while a search is pending.
      tap(v => {
        if (v && v.length >= 2) {
          this.loading.set(true);
        } else {
          this.loading.set(false);
          this.results.set([]);
        }
      }),
      debounceTime(250),
      switchMap(v => {
        const hash = this.store.hash();
        if (!hash || !v || v.length < 2) return of<MovieSummary[]>([]);
        return this.api.letterboxdSearch(hash, v).pipe(catchError(() => of<MovieSummary[]>([])));
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(results => {
      this.results.set(results);
      this.activeIndex.set(-1);
      this.loading.set(false);
    });
  }

  isConnected(movie: MovieSummary): boolean {
    return this.connectedIds.has(movie.id);
  }

  posterUrl(path: string | null): string | null {
    return path ? `${POSTER_W92}${path}` : null;
  }

  select(movie: MovieSummary): void {
    if (this.isConnected(movie)) this.pick.emit(movie.id);
  }

  onKeydown(event: KeyboardEvent): void {
    const len = this.results().length;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.activeIndex.update(i => Math.min(i + 1, len - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.activeIndex.update(i => Math.max(i - 1, -1));
    } else if (event.key === 'Enter') {
      const m = this.results()[this.activeIndex()];
      if (m) this.select(m);
    } else if (event.key === 'Escape') {
      this.closed.emit();
    }
  }
}

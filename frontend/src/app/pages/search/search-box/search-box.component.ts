import { Component, inject, signal, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { debounceTime, distinctUntilChanged, switchMap, catchError } from 'rxjs/operators';
import { of } from 'rxjs';
import { MovieApiService } from '../../../services/movie-api.service';
import { GraphStore } from '../../../services/graph-store.service';
import { MovieSummary } from '../../../models/movie.model';

const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';

@Component({
  selector: 'app-search-box',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './search-box.component.html',
  styleUrl: './search-box.component.scss'
})
export class SearchBoxComponent {
  private router = inject(Router);
  private api = inject(MovieApiService);
  private graphStore = inject(GraphStore);
  private destroyRef = inject(DestroyRef);

  readonly searchControl = new FormControl('');
  readonly results = signal<MovieSummary[]>([]);
  readonly activeIndex = signal(-1);
  readonly loading = signal(false);
  readonly open = signal(false);

  constructor() {
    this.searchControl.valueChanges.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap(v => {
        if (!v || v.length < 2) {
          this.loading.set(false);
          this.results.set([]);
          this.open.set(false);
          return of([]);
        }
        this.loading.set(true);
        return this.api.search(v).pipe(catchError(() => of([])));
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(results => {
      this.results.set(results);
      this.activeIndex.set(-1);
      this.open.set(results.length > 0);
      this.loading.set(false);
    });
  }

  posterUrl(path: string | null): string | null {
    return path ? `${POSTER_W92}${path}` : null;
  }

  highlight(title: string, query: string): { pre: string; match: string; post: string } {
    if (!query) return { pre: title, match: '', post: '' };
    const q = query.toLowerCase();
    const t = title.toLowerCase();
    if (t.startsWith(q)) {
      return { pre: '', match: title.slice(0, q.length), post: title.slice(q.length) };
    }
    return { pre: title, match: '', post: '' };
  }

  onKeydown(event: KeyboardEvent): void {
    if (!this.open()) return;
    const len = this.results().length;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.activeIndex.update(i => Math.min(i + 1, len - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.activeIndex.update(i => Math.max(i - 1, -1));
    } else if (event.key === 'Enter') {
      const item = this.results()[this.activeIndex()];
      if (item) this.select(item);
    } else if (event.key === 'Escape') {
      this.close();
    }
  }

  select(movie: MovieSummary): void {
    this.searchControl.setValue(movie.title, { emitEvent: false });
    this.close();
    this.graphStore.resetLimit();
    this.router.navigate(['/film', movie.id]);
  }

  close(): void {
    this.open.set(false);
    this.activeIndex.set(-1);
  }
}

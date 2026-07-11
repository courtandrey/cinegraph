import { Component, DestroyRef, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { catchError, debounceTime, switchMap, tap } from 'rxjs/operators';
import { of } from 'rxjs';
import { MovieApiService } from '../../../services/movie-api.service';
import { CompatSearchResult } from '../../../models/movie.model';

const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';

@Component({
  selector: 'app-compat-search',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './compat-search.component.html',
  styleUrl: './compat-search.component.scss'
})
export class CompatSearchComponent {
  private api = inject(MovieApiService);
  private destroyRef = inject(DestroyRef);
  private doc = inject(DOCUMENT);
  private prevViewport = '';

  @Input({ required: true }) hash!: string;
  @Output() pick = new EventEmitter<number>();
  @Output() closed = new EventEmitter<void>();

  readonly searchControl = new FormControl('');
  readonly results = signal<CompatSearchResult[]>([]);
  readonly loading = signal(false);
  readonly activeIndex = signal(-1);

  constructor() {
    this.searchControl.valueChanges.pipe(
      tap(v => {
        if (v && v.length >= 2) {
          this.loading.set(true);
        } else {
          this.loading.set(false);
          this.results.set([]);
        }
      }),
      debounceTime(250),
      switchMap(v =>
        (!v || v.length < 2 || !this.hash)
          ? of<CompatSearchResult[]>([])
          : this.api.letterboxdSearchGlobal(this.hash, v).pipe(catchError(() => of<CompatSearchResult[]>([])))),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(results => {
      this.results.set(results);
      this.activeIndex.set(-1);
      this.loading.set(false);
    });

    this.lockViewportZoom();
    this.destroyRef.onDestroy(() => this.restoreViewportZoom());
  }

  private lockViewportZoom(): void {
    const meta = this.doc.querySelector('meta[name="viewport"]');
    if (!meta) return;
    this.prevViewport = meta.getAttribute('content') ?? '';
    meta.setAttribute('content', 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no');
  }

  private restoreViewportZoom(): void {
    const meta = this.doc.querySelector('meta[name="viewport"]');
    if (meta) meta.setAttribute('content', this.prevViewport || 'width=device-width, initial-scale=1');
  }

  private pressedOnBackdrop = false;

  onBackdropMouseDown(e: MouseEvent): void {
    this.pressedOnBackdrop = e.target === e.currentTarget;
  }

  onBackdropClick(e: MouseEvent): void {
    if (this.pressedOnBackdrop && e.target === e.currentTarget) this.closed.emit();
  }

  posterUrl(path: string | null): string | null {
    return path ? `${POSTER_W92}${path}` : null;
  }

  select(movie: CompatSearchResult): void {
    if (movie.fromSet) return;
    this.pick.emit(movie.id);
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
      if (m && !m.fromSet) this.select(m);
    } else if (event.key === 'Escape') {
      this.closed.emit();
    }
  }
}

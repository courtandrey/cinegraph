import { Component, DestroyRef, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { catchError, debounceTime, switchMap, tap } from 'rxjs/operators';
import { of } from 'rxjs';
import { MovieApiService } from '../../../services/movie-api.service';
import { LetterboxdSearchResult } from '../../../models/movie.model';

const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';

@Component({
  selector: 'app-letterboxd-path-modal',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './letterboxd-path-modal.component.html',
  styleUrl: './letterboxd-path-modal.component.scss'
})
export class LetterboxdPathModalComponent {
  @Input() fromTitle = '';
  @Input({ required: true }) hash!: string;
  @Input({ required: true }) graphId!: number;
  @Input() excludeId: number | null = null;

  @Output() pick = new EventEmitter<number>();
  @Output() closed = new EventEmitter<void>();

  private api = inject(MovieApiService);
  private destroyRef = inject(DestroyRef);
  private doc = inject(DOCUMENT);
  private prevViewport = '';

  readonly searchControl = new FormControl('');
  readonly results = signal<LetterboxdSearchResult[]>([]);
  readonly loading = signal(false);

  constructor() {
    this.searchControl.valueChanges.pipe(
      tap(v => {
        if (v && v.length >= 2) { this.loading.set(true); }
        else { this.loading.set(false); this.results.set([]); }
      }),
      debounceTime(250),
      switchMap(v =>
        (!v || v.length < 2)
          ? of<LetterboxdSearchResult[]>([])
          : this.api.letterboxdSearch(this.hash, v).pipe(catchError(() => of<LetterboxdSearchResult[]>([])))),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(res => {
      this.results.set(res.filter(m => m.graphId === this.graphId && m.id !== this.excludeId));
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

  select(movie: LetterboxdSearchResult): void {
    this.pick.emit(movie.id);
  }
}

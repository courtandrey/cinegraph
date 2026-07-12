import { Component, DestroyRef, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { catchError, debounceTime, switchMap, tap } from 'rxjs/operators';
import { of } from 'rxjs';
import { MovieApiService } from '../../../services/movie-api.service';
import { SeoService } from '../../../services/seo.service';
import { MovieSummary, PathResult } from '../../../models/movie.model';

const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';

@Component({
  selector: 'app-path-modal',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './path-modal.component.html',
  styleUrl: './path-modal.component.scss'
})
export class PathModalComponent {
  @Input({ required: true }) fromId!: number;
  @Input() fromTitle = '';
  @Output() closed = new EventEmitter<void>();

  private api = inject(MovieApiService);
  private seo = inject(SeoService);
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);
  private doc = inject(DOCUMENT);
  private prevViewport = '';

  readonly searchControl = new FormControl('');
  readonly results = signal<MovieSummary[]>([]);
  readonly searching = signal(false);
  readonly computing = signal(false);
  readonly result = signal<PathResult | null>(null);
  readonly linkCopied = signal(false);
  private copiedTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.searchControl.valueChanges.pipe(
      tap(v => {
        this.result.set(null);
        if (v && v.length >= 2) { this.searching.set(true); }
        else { this.searching.set(false); this.results.set([]); }
      }),
      debounceTime(250),
      switchMap(v =>
        (!v || v.length < 2)
          ? of<MovieSummary[]>([])
          : this.api.search(v).pipe(catchError(() => of<MovieSummary[]>([])))),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(r => {
      this.results.set(r.filter(m => m.id !== this.fromId));
      this.searching.set(false);
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

  pickTarget(movie: MovieSummary): void {
    this.results.set([]);
    this.searchControl.setValue(movie.title, { emitEvent: false });
    this.computing.set(true);
    this.result.set(null);
    this.api.findPath(this.fromId, movie.id).subscribe({
      next: res => { this.result.set(res); this.computing.set(false); },
      error: () => {
        this.result.set({ found: false, reason: 'error', hops: 0, nodes: [], edges: [] });
        this.computing.set(false);
      }
    });
  }

  reasonText(reason: string | null): string {
    switch (reason) {
      case 'loading': return 'Graph is reloading. Please try searching later.';
      case 'busy': return 'Service is busy right now — try again in a moment.';
      case 'unreachable': return 'No path — these films aren’t connected.';
      case 'budget': return 'These films are too far apart to connect quickly.';
      case 'not_found': return 'Film not found.';
      default: return 'Could not find a path.';
    }
  }

  openFilm(id: number): void {
    this.closed.emit();
    this.router.navigate(['/film', id]);
  }

  sharePath(result: PathResult): void {
    const last = result.nodes[result.nodes.length - 1];
    if (!last) return;
    const url = `${this.seo.siteUrl}/path/${this.fromId}/${last.id}`;
    navigator.clipboard.writeText(url).then(
      () => this.flagCopied(),
      () => { navigator.share?.({ url }).catch(() => {}); }
    );
  }

  private flagCopied(): void {
    this.linkCopied.set(true);
    if (this.copiedTimer) clearTimeout(this.copiedTimer);
    this.copiedTimer = setTimeout(() => this.linkCopied.set(false), 2000);
  }
}

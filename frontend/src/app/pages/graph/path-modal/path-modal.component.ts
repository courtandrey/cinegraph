import { Component, DestroyRef, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { catchError, debounceTime, switchMap, tap } from 'rxjs/operators';
import { of } from 'rxjs';
import { MovieApiService } from '../../../services/movie-api.service';
import { MovieSummary, PathResult } from '../../../models/movie.model';

const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';

/** Picks a destination film and shows the shortest path (fewest hops) to it. */
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
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);

  readonly searchControl = new FormControl('');
  readonly results = signal<MovieSummary[]>([]);
  readonly searching = signal(false);
  readonly computing = signal(false);
  readonly result = signal<PathResult | null>(null);

  constructor() {
    this.searchControl.valueChanges.pipe(
      tap(v => {
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
}

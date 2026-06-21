import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { GraphCanvasComponent } from '../graph/graph-canvas/graph-canvas.component';
import { LetterboxdStore } from '../../services/letterboxd-store.service';
import { GraphStore } from '../../services/graph-store.service';
import { MovieApiService } from '../../services/movie-api.service';
import { GraphPayload, GraphNode, MovieDetail } from '../../models/movie.model';

const POSTER_W342 = 'https://image.tmdb.org/t/p/w342';

@Component({
  selector: 'app-letterboxd-graph',
  standalone: true,
  imports: [GraphCanvasComponent],
  templateUrl: './letterboxd-graph.component.html',
  styleUrl: './letterboxd-graph.component.scss'
})
export class LetterboxdGraphComponent implements OnInit, OnDestroy {
  readonly store = inject(LetterboxdStore);
  private graphStore = inject(GraphStore);
  readonly router = inject(Router);
  private route = inject(ActivatedRoute);
  private api = inject(MovieApiService);

  readonly selectedNodeId = signal<number | null>(null);
  readonly selectedDetail = signal<MovieDetail | null>(null);
  readonly status = signal<'loading' | 'ready' | 'error'>('ready');
  readonly thresholdDisplay = signal(this.store.inScoreThreshold());
  private thresholdTimer: ReturnType<typeof setTimeout> | null = null;

  readonly inScoreById = computed(() => {
    const map = new Map<number, number>();
    const g = this.store.current();
    if (!g) return map;
    for (const n of g.nodes) map.set(n.id, n.inScore);
    return map;
  });

  readonly maxInScore = computed(() => {
    let max = 0;
    this.inScoreById().forEach(v => { if (v > max) max = v; });
    return Math.ceil(max);
  });

  readonly payload = computed<GraphPayload | null>(() => {
    const g = this.store.current();
    if (!g) return null;
    const scores = this.inScoreById();
    const threshold = this.store.inScoreThreshold();

    const keep = new Set<number>(
      g.nodes.filter(n => (scores.get(n.id) ?? 0) >= threshold).map(n => n.id)
    );
    const nodes = g.nodes.filter(n => keep.has(n.id));
    if (!nodes.length) return null;
    const edges = g.edges.filter(e => keep.has(e.source) && keep.has(e.target));
    const center = nodes.find(n => n.id === g.centerId) ?? nodes[0];
    return { center: toDetailLike(center), nodes, edges };
  });

  readonly centerId = computed(() => this.payload()?.center.id ?? 0);

  readonly selectedInScore = computed(() => {
    const id = this.selectedNodeId();
    return id == null ? 0 : (this.inScoreById().get(id) ?? 0);
  });

  private detailSeq = 0;

  ngOnInit(): void {
    const hash = this.route.snapshot.paramMap.get('hash');
    if (!hash) { this.router.navigate(['/']); return; }
    if (this.store.hash() === hash && this.store.count() > 0) return;

    this.status.set('loading');
    this.api.letterboxdOverview(hash).subscribe({
      next: graphs => {
        if (!graphs.length) { this.status.set('error'); return; }
        this.store.set(hash, graphs);
        this.status.set('ready');
      },
      error: () => this.status.set('error')
    });
  }

  ngOnDestroy(): void {
    if (this.thresholdTimer) clearTimeout(this.thresholdTimer);
  }

  onNodeTap(id: number): void {
    this.selectedNodeId.set(id);
    this.selectedDetail.set(null);
    const seq = ++this.detailSeq;
    this.api.getMovie(id).subscribe({
      next: detail => { if (seq === this.detailSeq) this.selectedDetail.set(detail); },
      error: () => { if (seq === this.detailSeq) this.selectedDetail.set(null); }
    });
  }

  onClearSelection(): void {
    this.selectedNodeId.set(null);
    this.selectedDetail.set(null);
  }

  deepDive(): void {
    const id = this.selectedNodeId();
    const hash = this.store.hash();
    if (id == null || !hash) return;
    this.graphStore.setMinScore(0);
    this.graphStore.resetLimit();
    this.router.navigate(['/letterboxd', hash, 'film', id]);
  }

  setThreshold(v: number): void {
    this.thresholdDisplay.set(v);
    if (this.thresholdTimer) clearTimeout(this.thresholdTimer);
    this.thresholdTimer = setTimeout(() => this.store.setInScoreThreshold(v), 500);
  }

  private resetThreshold(): void {
    if (this.thresholdTimer) clearTimeout(this.thresholdTimer);
    this.thresholdDisplay.set(0);
    this.store.setInScoreThreshold(0);
  }

  prev(): void {
    this.onClearSelection();
    this.resetThreshold();
    this.store.prev();
  }

  next(): void {
    this.onClearSelection();
    this.resetThreshold();
    this.store.next();
  }

  posterW342(path: string | null): string | null {
    return path ? `${POSTER_W342}${path}` : null;
  }
}

function toDetailLike(node: GraphNode): MovieDetail {
  return {
    id: node.id,
    title: node.title,
    year: node.year,
    posterPath: node.posterPath,
    originalTitle: node.title,
    releaseDate: null,
    overview: '',
    runtime: null,
    genres: [],
    countries: [],
    voteAverage: 0
  };
}

import { Component, OnDestroy, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { GraphCanvasComponent } from '../graph/graph-canvas/graph-canvas.component';
import { LetterboxdSearchComponent } from './letterboxd-search/letterboxd-search.component';
import { LetterboxdPathModalComponent } from './letterboxd-path-modal/letterboxd-path-modal.component';
import { LetterboxdStore } from '../../services/letterboxd-store.service';
import { GraphStore } from '../../services/graph-store.service';
import { MovieApiService } from '../../services/movie-api.service';
import { GraphPayload, GraphNode, GraphEdge, MovieDetail } from '../../models/movie.model';

interface PathView {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

type DetailReturn = 'recs' | null;

const POSTER_W342 = 'https://image.tmdb.org/t/p/w342';
const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';

@Component({
  selector: 'app-letterboxd-graph',
  standalone: true,
  imports: [GraphCanvasComponent, LetterboxdSearchComponent, LetterboxdPathModalComponent],
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
  readonly searchOpen = signal(false);
  readonly forcedNodeIds = signal<Set<number>>(new Set());
  readonly focusTarget = signal<{ id: number } | null>(null);

  readonly pathSearchOpen = signal(false);
  readonly path = signal<PathView | null>(null);
  readonly pathSheetOpen = signal(false);
  readonly pathNodeIds = computed(() => this.path()?.nodes.map(n => n.id) ?? null);

  readonly recsOpen = signal(false);
  readonly recs = signal<GraphNode[] | null>(null);
  readonly recsStatus = signal<'idle' | 'loading' | 'error'>('idle');
  readonly recsScope = signal<'all' | 'component'>('all');
  readonly recsInvert = signal(false);
  private recsKey: string | null = null;
  private recsSeq = 0;

  readonly detailReturn = signal<DetailReturn>(null);
  readonly pathReturn = signal<{ nodeId: number; detailReturn: DetailReturn } | null>(null);

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
    const forced = this.forcedNodeIds();

    const keep = new Set<number>(
      g.nodes.filter(n => (scores.get(n.id) ?? 0) >= threshold || forced.has(n.id)).map(n => n.id)
    );
    const nodes = g.nodes.filter(n => keep.has(n.id));
    if (!nodes.length) return null;
    const edges = g.edges.filter(e => keep.has(e.source) && keep.has(e.target));
    const center = nodes.find(n => n.id === g.centerId) ?? nodes[0];
    return { center: toDetailLike(center), nodes, edges };
  });

  readonly centerId = computed(() => this.payload()?.center.id ?? 0);

  readonly recScoreById = computed(() => {
    const map = new Map<number, number>();
    for (const r of this.recs() ?? []) map.set(r.id, r.inScore);
    return map;
  });

  readonly selectedInScore = computed(() => {
    const id = this.selectedNodeId();
    if (id == null) return 0;
    return this.inScoreById().get(id) ?? this.recScoreById().get(id) ?? 0;
  });

  readonly isRecommendation = computed(() => {
    const id = this.selectedNodeId();
    return id != null && !this.inScoreById().has(id) && this.recScoreById().has(id);
  });

  readonly selectedScoreLabel = computed(() =>
    this.isRecommendation() ? 'Recommendation score' : 'Total in-score');

  private detailSeq = 0;

  constructor() {
    effect(() => {
      if (!this.recsOpen()) return;
      const scope = this.recsScope();
      const invert = this.recsInvert();
      const graphId = scope === 'component' ? this.store.current()?.centerId ?? 0 : null;
      untracked(() => this.ensureRecs(scope, graphId, invert));
    });
  }

  private ensureRecs(scope: 'all' | 'component', graphId: number | null, invert: boolean): void {
    const key = `${scope}:${graphId ?? ''}:${invert}`;
    if (this.recsKey === key && (this.recs() !== null || this.recsStatus() === 'loading')) return;
    const hash = this.store.hash();
    if (!hash) return;
    this.recsKey = key;
    this.recs.set(null);
    this.recsStatus.set('loading');
    const seq = ++this.recsSeq;
    this.api.letterboxdRecommendations(hash, 25, graphId ?? undefined, invert).subscribe({
      next: list => { if (seq === this.recsSeq) { this.recs.set(list); this.recsStatus.set('idle'); } },
      error: () => { if (seq === this.recsSeq) this.recsStatus.set('error'); }
    });
  }

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
    if (this.path()) this.dismissPath();
    this.recsOpen.set(false);
    this.detailReturn.set(null);
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
    this.forcedNodeIds.set(new Set());
    this.dismissPath();
  }

  openSearch(): void {
    this.searchOpen.set(true);
  }

  openRecs(): void {
    if (this.path()) this.dismissPath();
    this.onClearSelection();
    this.recsOpen.set(true);
  }

  setRecsScope(scope: 'all' | 'component'): void {
    this.recsScope.set(scope);
  }

  toggleInvert(): void {
    this.recsInvert.update(v => !v);
  }

  onRecTap(rec: GraphNode): void {
    this.onNodeTap(rec.id);
    this.detailReturn.set('recs');
  }

  backToRecs(): void {
    this.openRecs();
  }

  onSearchPick(sel: { movieId: number; graphId: number }): void {
    this.searchOpen.set(false);
    const idx = this.store.graphIndexByCenter(sel.graphId);
    if (idx !== -1 && idx !== this.store.index()) {
      this.resetThreshold();
      this.store.setIndex(idx);
    }

    const g = this.store.current();
    if (!g) return;

    const inGraph = g.nodes.some(n => n.id === sel.movieId);
    const hasVisibleEdge = g.edges.some(e => e.source === sel.movieId || e.target === sel.movieId);
    if (inGraph && hasVisibleEdge) {
      this.revealAndFocus(sel.movieId);
      return;
    }

    const hash = this.store.hash();
    if (!hash) return;
    this.api.letterboxdAttach(hash, sel.movieId, g.nodes.map(n => n.id)).subscribe({
      next: att => {
        if (att.edges.length > 0) {
          this.store.mergeIntoCurrent(att.node, att.edges);
          this.revealAndFocus(sel.movieId);
        } else {
          this.store.mergeIntoCurrent(att.node, []);
          this.connectViaPath(sel.movieId, g.centerId);
        }
      },
      error: () => this.connectViaPath(sel.movieId, g.centerId)
    });
  }

  private connectViaPath(movieId: number, targetId: number): void {
    const hash = this.store.hash();
    if (!hash) { this.revealAndFocus(movieId); return; }
    this.api.letterboxdPath(hash, movieId, targetId).subscribe({
      next: res => {
        if (res.found && res.nodes.length) {
          this.store.mergeManyIntoCurrent(res.nodes, res.edges);
          this.forceReveal(res.nodes.map(n => n.id));
        }
        this.revealAndFocus(movieId);
      },
      error: () => this.revealAndFocus(movieId)
    });
  }

  private forceReveal(ids: number[]): void {
    this.forcedNodeIds.update(s => {
      const next = new Set(s);
      ids.forEach(id => next.add(id));
      return next;
    });
  }

  private revealAndFocus(movieId: number): void {
    this.forceReveal([movieId]);
    this.onNodeTap(movieId);
    this.focusTarget.set({ id: movieId });
  }

  openPathSearch(): void {
    if (this.selectedNodeId() == null) return;
    this.pathSearchOpen.set(true);
  }

  onPathPick(targetId: number): void {
    this.pathSearchOpen.set(false);
    const sourceId = this.selectedNodeId();
    const hash = this.store.hash();
    if (sourceId == null || !hash || sourceId === targetId) return;
    const from = { nodeId: sourceId, detailReturn: this.detailReturn() };
    this.api.letterboxdPath(hash, sourceId, targetId).subscribe({
      next: res => {
        if (!res.found || res.nodes.length < 2) return;
        this.store.mergeManyIntoCurrent(res.nodes, res.edges);
        this.forceReveal(res.nodes.map(n => n.id));
        this.onClearSelection();
        this.pathReturn.set(from);
        this.path.set({ nodes: res.nodes, edges: res.edges });
        this.pathSheetOpen.set(false);
      }
    });
  }

  dismissPath(): void {
    this.path.set(null);
    this.pathSheetOpen.set(false);
    this.pathReturn.set(null);
  }

  dismissPathToSource(): void {
    const ret = this.pathReturn();
    this.dismissPath();
    if (ret) {
      this.onNodeTap(ret.nodeId);
      this.detailReturn.set(ret.detailReturn);
    }
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

  focusNode(id: number): void {
    this.focusTarget.set({ id });
  }

  pathRightInset(): number {
    return window.innerWidth > 820 ? 340 : 0;
  }

  readonly pathGraphId = computed(() => this.store.current()?.centerId ?? 0);

  readonly pathSourceTitle = computed(() => {
    const g = this.store.current();
    const src = this.selectedNodeId();
    return g?.nodes.find(n => n.id === src)?.title ?? '';
  });

  posterW342(path: string | null): string | null {
    return path ? `${POSTER_W342}${path}` : null;
  }

  posterW92(path: string | null): string | null {
    return path ? `${POSTER_W92}${path}` : null;
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

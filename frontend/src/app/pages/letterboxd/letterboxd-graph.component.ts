import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ClusterMapComponent, ClusterPair } from './cluster-map/cluster-map.component';
import { FilmSharesComponent } from './film-shares/film-shares.component';
import { clusterColors } from './cluster-map/cluster-colors';
import { ClusterPanelComponent, NeighbourCluster } from './cluster-panel/cluster-panel.component';
import { LetterboxdSearchComponent } from './letterboxd-search/letterboxd-search.component';
import { CompatSearchComponent } from './compat-search/compat-search.component';
import { LetterboxdPathModalComponent } from './letterboxd-path-modal/letterboxd-path-modal.component';
import { LetterboxdStore } from '../../services/letterboxd-store.service';
import { GraphStore } from '../../services/graph-store.service';
import { MovieApiService } from '../../services/movie-api.service';
import { SeoService } from '../../services/seo.service';
import { GraphNode, GraphEdge, LetterboxdMap, MovieDetail } from '../../models/movie.model';

interface PathView {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

type DetailReturn = 'recs' | null;

const POSTER_W342 = 'https://image.tmdb.org/t/p/w342';
const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';
const MIN_RUNTIME_STEPS = [0, 5, 10, 20, 30, 40, 50, 60];

@Component({
  selector: 'app-letterboxd-graph',
  standalone: true,
  imports: [ClusterMapComponent, ClusterPanelComponent, FilmSharesComponent, LetterboxdSearchComponent, CompatSearchComponent, LetterboxdPathModalComponent],
  templateUrl: './letterboxd-graph.component.html',
  styleUrl: './letterboxd-graph.component.scss'
})
export class LetterboxdGraphComponent implements OnInit, OnDestroy {
  @ViewChild(ClusterMapComponent) private clusterMap?: ClusterMapComponent;

  readonly store = inject(LetterboxdStore);
  private graphStore = inject(GraphStore);
  readonly router = inject(Router);
  private route = inject(ActivatedRoute);
  private api = inject(MovieApiService);
  private seo = inject(SeoService);

  readonly selectedNodeId = signal<number | null>(null);
  readonly selectedDetail = signal<MovieDetail | null>(null);
  readonly status = signal<'loading' | 'ready' | 'error'>('ready');
  readonly thresholdDisplay = signal(this.store.inScoreThreshold());
  readonly searchOpen = signal(false);
  readonly compatSearchOpen = signal(false);
  readonly compatId = signal<number | null>(null);
  readonly compatScoreValue = signal<number | null>(null);
  readonly compatNotConnected = signal(false);
  readonly forcedNodeIds = signal<Set<number>>(new Set());
  readonly focusTarget = signal<{ id: number } | null>(null);
  readonly selectedClusterId = signal<number | null>(null);
  readonly focusCluster = signal<{ id: number } | null>(null);
  readonly selectedLink = signal<ClusterPair | null>(null);

  readonly pathSearchOpen = signal(false);
  readonly path = signal<PathView | null>(null);
  readonly pathSheetOpen = signal(false);
  readonly pathNodeIds = computed(() => this.path()?.nodes.map(n => n.id) ?? null);

  readonly recsOpen = signal(false);
  readonly recs = signal<GraphNode[] | null>(null);
  readonly recsStatus = signal<'idle' | 'loading' | 'error'>('idle');
  readonly recsScope = signal<'all' | 'cluster'>('all');
  readonly recsClusterId = signal<number | null>(null);
  readonly recsInvert = signal(false);
  readonly minRuntimeSteps = MIN_RUNTIME_STEPS;
  readonly recsMinRuntime = signal(0);
  readonly minRuntimeDisplay = signal(0);
  private recsKey: string | null = null;
  private recsSeq = 0;

  readonly currentRecsKey = computed(() =>
    recsKeyOf(this.recsScopeCluster(), this.recsInvert(), this.recsMinRuntime()));

  readonly recsScopeCluster = computed(() =>
    this.recsScope() === 'cluster' ? this.recsClusterId() : null);

  private recsScrollEl: HTMLElement | null = null;

  @ViewChild('recsScroll')
  set recsScrollRef(ref: ElementRef<HTMLElement> | undefined) {
    this.recsScrollEl = ref?.nativeElement ?? null;
  }

  readonly detailReturn = signal<DetailReturn>(null);
  readonly pathReturn = signal<{ nodeId: number; detailReturn: DetailReturn } | null>(null);

  private thresholdTimer: ReturnType<typeof setTimeout> | null = null;

  readonly inScoreById = computed(() => {
    const map = new Map<number, number>();
    for (const n of this.store.map()?.nodes ?? []) map.set(n.id, n.inScore);
    return map;
  });

  readonly maxInScore = computed(() => {
    let max = 0;
    this.inScoreById().forEach(v => { if (v > max) max = v; });
    return Math.ceil(max);
  });

  readonly viewMap = computed<LetterboxdMap | null>(() => {
    const map = this.store.map();
    if (!map || !map.nodes.length) return null;
    const scores = this.inScoreById();
    const threshold = this.store.inScoreThreshold();
    const forced = this.forcedNodeIds();
    const keep = new Set<number>(
      map.nodes.filter(n => (scores.get(n.id) ?? 0) >= threshold || forced.has(n.id)).map(n => n.id)
    );
    return {
      ...map,
      nodes: map.nodes.filter(n => keep.has(n.id)),
      films: map.films.filter(f => keep.has(f.movieId))
    };
  });

  readonly colors = computed(() => clusterColors(this.store.map()?.clusters ?? []));

  readonly labels = computed(() => {
    const titles = new Map((this.store.map()?.nodes ?? []).map(n => [n.id, n.title]));
    const out = new Map<number, string>();
    for (const c of this.store.map()?.clusters ?? []) {
      out.set(c.id, c.kind === 'CLUSTER' ? c.contributors[0]?.name ?? titles.get(c.hubId) ?? '' : 'Small group');
    }
    return out;
  });

  readonly selectedFilm = computed(() => {
    const id = this.selectedNodeId();
    return id == null ? null : this.store.filmById().get(id) ?? null;
  });

  readonly linkSummary = computed(() => {
    const pair = this.selectedLink();
    const link = pair && this.store.map()?.links.find(l => l.source === pair.a && l.target === pair.b);
    if (!pair || !link) return null;
    const label = (id: number) => this.labels().get(id) || 'Small group';
    return {
      labelA: label(pair.a), colorA: this.colors().get(pair.a),
      labelB: label(pair.b), colorB: this.colors().get(pair.b),
      edges: link.edges
    };
  });

  readonly selectedCluster = computed(() => {
    const id = this.selectedClusterId();
    return id == null ? null : this.store.clusterById().get(id) ?? null;
  });

  readonly selectedClusterBridges = computed(() => {
    const id = this.selectedClusterId();
    return (this.store.map()?.films ?? []).filter(f => f.clusterId === id && f.placement === 'BRIDGE').length;
  });

  readonly selectedClusterHub = computed(() => {
    const c = this.selectedCluster();
    return c ? this.store.map()?.nodes.find(n => n.id === c.hubId) ?? null : null;
  });

  readonly neighbours = computed<NeighbourCluster[]>(() => {
    const id = this.selectedClusterId();
    const map = this.store.map();
    if (id == null || !map) return [];
    const byId = this.store.clusterById();
    return map.links
      .filter(l => l.source === id || l.target === id)
      .map(l => ({ other: l.source === id ? l.target : l.source, affinity: l.affinity }))
      .filter(l => byId.get(l.other)?.kind === 'CLUSTER')
      .sort((a, b) => b.affinity - a.affinity)
      .slice(0, 4)
      .map(l => ({ id: l.other, label: this.labels().get(l.other) ?? '', color: this.colors().get(l.other) ?? '' }));
  });

  readonly recScoreById = computed(() => {
    const map = new Map<number, number>();
    for (const r of this.recs() ?? []) map.set(r.id, r.inScore);
    return map;
  });

  readonly selectedInScore = computed(() => {
    const id = this.selectedNodeId();
    if (id == null) return 0;
    if (this.compatId() === id) return this.compatScoreValue() ?? 0;
    return this.inScoreById().get(id) ?? this.recScoreById().get(id) ?? 0;
  });

  readonly isRecommendation = computed(() => {
    const id = this.selectedNodeId();
    if (id == null) return false;
    if (this.compatId() === id) return true;
    return !this.inScoreById().has(id) && this.recScoreById().has(id);
  });

  readonly compatChecking = computed(() => {
    const id = this.selectedNodeId();
    return id != null && this.compatId() === id
      && this.compatScoreValue() === null && !this.compatNotConnected();
  });

  readonly selectedNotConnected = computed(() => {
    const id = this.selectedNodeId();
    return id != null && this.compatId() === id && this.compatNotConnected();
  });

  readonly selectedScoreLabel = computed(() =>
    this.isRecommendation() ? 'Recommendation score' : 'Total in-score');

  readonly recsScopeLabel = computed(() => {
    const id = this.recsClusterId();
    return id == null ? '' : this.labels().get(id) ?? '';
  });

  private detailSeq = 0;

  constructor() {
    effect(() => {
      if (!this.recsOpen()) return;
      const clusterId = this.recsScopeCluster();
      const invert = this.recsInvert();
      const minRuntime = this.recsMinRuntime();
      untracked(() => this.ensureRecs(clusterId, invert, minRuntime));
    });

    effect(() => {
      const ready = this.recsOpen() && this.recs() !== null;
      const key = this.currentRecsKey();
      if (!ready) return;
      untracked(() => this.restoreRecsScroll(key));
    });
  }

  private restoreRecsScroll(key: string): void {
    const top = this.store.recsScroll(key);
    requestAnimationFrame(() => {
      if (this.recsScrollEl) this.recsScrollEl.scrollTop = top;
    });
  }

  onRecsScroll(el: EventTarget | null): void {
    if (el instanceof HTMLElement) this.store.rememberRecsScroll(this.currentRecsKey(), el.scrollTop);
  }

  private ensureRecs(clusterId: number | null, invert: boolean, minRuntime: number): void {
    const key = recsKeyOf(clusterId, invert, minRuntime);
    if (this.recsKey === key && (this.recs() !== null || this.recsStatus() === 'loading')) return;
    const hash = this.store.hash();
    if (!hash) return;
    this.recsKey = key;
    this.recs.set(null);
    this.recsStatus.set('loading');
    const seq = ++this.recsSeq;
    this.api.letterboxdRecommendations(hash, 25, clusterId ?? undefined, invert, minRuntime).subscribe({
      next: list => { if (seq === this.recsSeq) { this.recs.set(list); this.recsStatus.set('idle'); } },
      error: () => { if (seq === this.recsSeq) this.recsStatus.set('error'); }
    });
  }

  ngOnInit(): void {
    this.seo.apply({
      title: 'Your Letterboxd recommendations | CineGraph',
      description: 'Personal movie recommendation graph built from your Letterboxd ratings.',
      noindex: true
    });
    const hash = this.route.snapshot.paramMap.get('hash');
    if (!hash) { this.router.navigate(['/']); return; }
    if (this.store.hash() === hash && this.store.loaded()) return;

    this.status.set('loading');
    this.api.letterboxdOverview(hash).subscribe({
      next: map => {
        if (!map.nodes.length) { this.status.set('error'); return; }
        this.store.set(hash, map);
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
    this.selectedClusterId.set(null);
    this.selectedLink.set(null);
    this.detailReturn.set(null);
    this.compatId.set(null);
    this.compatScoreValue.set(null);
    this.compatNotConnected.set(false);
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
    this.selectedClusterId.set(null);
    this.selectedLink.set(null);
  }

  onLinkTap(pair: ClusterPair): void {
    if (this.path()) this.dismissPath();
    this.recsOpen.set(false);
    this.onClearSelection();
    this.selectedLink.set(pair);
  }

  onClusterTap(id: number): void {
    if (this.path()) this.dismissPath();
    this.recsOpen.set(false);
    this.selectedNodeId.set(null);
    this.selectedDetail.set(null);
    this.selectedLink.set(null);
    this.selectedClusterId.set(id);
  }

  openCluster(id: number): void {
    this.onClusterTap(id);
    this.focusCluster.set({ id });
  }

  openClusterRecs(): void {
    const id = this.selectedClusterId();
    if (id == null) return;
    this.recsClusterId.set(id);
    this.recsScope.set('cluster');
    this.openRecs();
  }

  fitMap(): void {
    this.clusterMap?.fitAll();
  }

  deepDive(): void {
    const id = this.selectedNodeId();
    const hash = this.store.hash();
    if (id == null || !hash || this.selectedNotConnected()) return;
    this.graphStore.setMinScore(0);
    this.graphStore.resetLimit();
    this.router.navigate(['/letterboxd', hash, 'film', id],
      this.isRecommendation() ? { queryParams: { rec: 1 } } : {});
  }

  setThreshold(v: number): void {
    this.thresholdDisplay.set(v);
    if (this.thresholdTimer) clearTimeout(this.thresholdTimer);
    this.thresholdTimer = setTimeout(() => this.store.setInScoreThreshold(v), 500);
  }

  openSearch(): void {
    this.searchOpen.set(true);
  }

  openRecs(): void {
    if (this.path()) this.dismissPath();
    this.onClearSelection();
    this.recsOpen.set(true);
  }

  setRecsScope(scope: 'all' | 'cluster'): void {
    this.recsScope.set(scope);
  }

  toggleInvert(): void {
    this.recsInvert.update(v => !v);
  }

  previewMinRuntime(step: number): void {
    this.minRuntimeDisplay.set(MIN_RUNTIME_STEPS[step] ?? 0);
  }

  commitMinRuntime(step: number): void {
    this.previewMinRuntime(step);
    this.recsMinRuntime.set(this.minRuntimeDisplay());
  }

  minRuntimeStep(): number {
    return Math.max(0, MIN_RUNTIME_STEPS.indexOf(this.minRuntimeDisplay()));
  }

  onRecTap(rec: GraphNode): void {
    this.onNodeTap(rec.id);
    this.detailReturn.set('recs');
  }

  backToRecs(): void {
    this.openRecs();
  }

  openCompatSearch(): void {
    this.compatSearchOpen.set(true);
  }

  onCompatPick(movieId: number): void {
    this.compatSearchOpen.set(false);
    const hash = this.store.hash();
    if (!hash) return;
    this.onNodeTap(movieId);
    this.detailReturn.set('recs');
    this.compatId.set(movieId);
    this.compatScoreValue.set(null);
    this.compatNotConnected.set(false);
    this.api.letterboxdRecommendationBreakdown(hash, movieId).subscribe({
      next: bd => {
        if (this.compatId() !== movieId) return;
        if (!bd.contributions?.length) this.compatNotConnected.set(true);
        else this.compatScoreValue.set(bd.total);
      },
      error: () => { if (this.compatId() === movieId) this.compatNotConnected.set(true); }
    });
  }

  onSearchPick(sel: { movieId: number; graphId: number }): void {
    this.searchOpen.set(false);
    const map = this.store.map();
    const hash = this.store.hash();
    if (!map || !hash) return;
    if (this.store.filmById().has(sel.movieId)) {
      this.revealAndFocus(sel.movieId);
      return;
    }
    const anchor = this.componentAnchor(sel.graphId);
    this.api.letterboxdAttach(hash, sel.movieId, map.nodes.map(n => n.id)).subscribe({
      next: att => {
        this.store.merge([att.node], att.edges);
        if (att.edges.length > 0 || anchor == null) this.revealAndFocus(sel.movieId);
        else this.connectViaPath(sel.movieId, anchor);
      },
      error: () => anchor == null ? this.revealAndFocus(sel.movieId) : this.connectViaPath(sel.movieId, anchor)
    });
  }

  private componentAnchor(componentId: number): number | null {
    const clusters = (this.store.map()?.clusters ?? []).filter(c => c.componentId === componentId);
    return clusters.sort((a, b) => b.size - a.size)[0]?.hubId ?? null;
  }

  private connectViaPath(movieId: number, targetId: number): void {
    const hash = this.store.hash();
    if (!hash) { this.revealAndFocus(movieId); return; }
    this.api.letterboxdPath(hash, movieId, targetId).subscribe({
      next: res => {
        if (res.found && res.nodes.length) {
          this.store.merge(res.nodes, res.edges);
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
        this.store.merge(res.nodes, res.edges);
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

  focusNode(id: number): void {
    this.focusTarget.set({ id });
  }

  pathRightInset(): number {
    return window.innerWidth > 820 ? 340 : 0;
  }

  readonly pathGraphId = computed(() => this.selectedFilm()?.componentId ?? 0);

  readonly pathSourceTitle = computed(() => {
    const src = this.selectedNodeId();
    return this.store.map()?.nodes.find(n => n.id === src)?.title ?? '';
  });

  posterW342(path: string | null): string | null {
    return path ? `${POSTER_W342}${path}` : null;
  }

  posterW92(path: string | null): string | null {
    return path ? `${POSTER_W92}${path}` : null;
  }
}

function recsKeyOf(clusterId: number | null, invert: boolean, minRuntime: number): string {
  return `${clusterId ?? 'all'}:${invert}:${minRuntime}`;
}

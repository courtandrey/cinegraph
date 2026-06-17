import { Component, OnInit, OnDestroy, signal, computed, inject } from '@angular/core';
import { Location } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { GraphCanvasComponent } from './graph-canvas/graph-canvas.component';
import { FilmPanelComponent } from './film-panel/film-panel.component';
import { WeightsPanelComponent, RoleSlider } from './weights-panel/weights-panel.component';
import { MovieApiService } from '../../services/movie-api.service';
import { GraphStore } from '../../services/graph-store.service';
import { crewScoreOf, rolesInGraph, COMPONENT_DEFAULTS } from '../../services/graph-scoring';
import { GraphPayload, GraphNode, MovieDetail, EdgeBreakdown, RoleWeight } from '../../models/movie.model';

@Component({
  selector: 'app-graph',
  standalone: true,
  imports: [GraphCanvasComponent, FilmPanelComponent, WeightsPanelComponent],
  templateUrl: './graph.component.html',
  styleUrl: './graph.component.scss'
})
export class GraphComponent implements OnInit, OnDestroy {
  private route = inject(ActivatedRoute);
  readonly router = inject(Router);
  private location = inject(Location);
  private api = inject(MovieApiService);
  readonly store = inject(GraphStore);

  readonly loading = signal(false);
  readonly graph = signal<GraphPayload | null>(null);
  readonly centerId = signal(0);
  readonly hash = signal<string | null>(null);
  readonly selectedNodeId = signal<number | null>(null);
  readonly selectedBreakdown = signal<EdgeBreakdown | null>(null);
  readonly weightsOpen = signal(false);
  readonly infoOpen = signal(false);
  readonly defaultWeights = signal<ReadonlyMap<string, number>>(new Map());
  readonly minScoreDisplay = signal(this.store.minScore());
  private minScoreTimer: ReturnType<typeof setTimeout> | null = null;

  readonly limitOptions = [25, 40, 75, 100];

  readonly weightsActive = computed(() => this.store.customWeights() !== null);

  readonly maxScore = computed(() => {
    const g = this.graph();
    const cid = this.centerId();
    if (!g) return 60;
    const centerScores = g.edges
      .filter(e => e.source === cid || e.target === cid)
      .map(e => e.score);
    if (!centerScores.length) return 0;
    return Math.max(0, Math.floor(Math.max(...centerScores)) - 1);
  });

  readonly canGoBack = computed(() => this.store.visitedCenters().length > 1);

  goBack(): void {
    this.infoOpen.set(false);
    this.location.back();
  }

  readonly roleSliders = computed<RoleSlider[]>(() => {
    const g = this.graph();
    if (!g) return [];
    const custom = this.store.customWeights();
    return [...rolesInGraph(g).entries()]
      .map(([code, count]) => {
        const defaultWeight = this.effectiveDefault(code);
        return {
          code,
          count,
          defaultWeight,
          weight: custom?.get(code) ?? defaultWeight
        };
      })
      .sort((a, b) => b.count - a.count || b.weight - a.weight);
  });

  private graphRequestSeq = 0;
  private edgeRequestSeq = 0;

  ngOnInit(): void {
    this.api.getRoles().subscribe({
      next: roles => this.defaultWeights.set(toWeightMap(roles)),
      error: () => this.defaultWeights.set(new Map())
    });
    this.route.params.subscribe(params => {
      this.hash.set(params['hash'] ?? null);
      this.centerId.set(Number(params['id']));
      this.fetchGraph();
    });
  }

  fetchGraph(): void {
    const id = this.centerId();
    if (!id) return;
    this.loading.set(true);
    this.selectedNodeId.set(null);
    this.selectedBreakdown.set(null);

    const weights = this.store.customWeights();
    const hash = this.hash();
    const seq = ++this.graphRequestSeq;
    const request$ = hash
      ? (weights
          ? this.api.letterboxdReweight(hash, id, this.store.limit(), weights, this.store.minScore())
          : this.api.letterboxdRecenter(hash, id, this.store.minScore(), this.store.limit()))
      : (weights
          ? this.api.reweightGraph(id, this.store.limit(), weights, this.store.minScore())
          : this.api.getGraph(id, this.store.minScore(), this.store.limit()));

    request$.subscribe({
      next: payload => {
        if (seq !== this.graphRequestSeq) return;
        this.graph.set(payload);
        this.loading.set(false);
        this.store.addVisited({
          id: payload.center.id,
          title: payload.center.title,
          year: payload.center.year,
          posterPath: payload.center.posterPath
        });
      },
      error: () => {
        if (seq === this.graphRequestSeq) this.loading.set(false);
      }
    });
  }

  onNodeTap(nodeId: number): void {
    if (nodeId === this.centerId()) {
      this.onClearSelection();
      return;
    }
    this.selectedNodeId.set(nodeId);
    this.selectedBreakdown.set(null);
    this.infoOpen.set(true);

    if (this.weightsActive()) {
      this.selectedBreakdown.set(this.localBreakdown(nodeId));
      return;
    }

    const seq = ++this.edgeRequestSeq;
    this.api.getEdge(this.centerId(), nodeId).subscribe({
      next: bd => {
        if (seq === this.edgeRequestSeq) this.selectedBreakdown.set(bd);
      },
      error: () => {
        if (seq === this.edgeRequestSeq) this.selectedBreakdown.set(null);
      }
    });
  }

  onClearSelection(): void {
    this.selectedNodeId.set(null);
    this.selectedBreakdown.set(null);
  }

  onReCenter(id: number): void {
    this.infoOpen.set(false);
    const hash = this.hash();
    this.router.navigate(hash ? ['/letterboxd', hash, 'film', id] : ['/film', id]);
  }

  exit(): void {
    const hash = this.hash();
    this.router.navigate(hash ? ['/letterboxd', hash] : ['/']);
  }

  onMinScoreInput(v: number): void {
    this.minScoreDisplay.set(v);
    if (this.minScoreTimer) clearTimeout(this.minScoreTimer);
    this.minScoreTimer = setTimeout(() => this.onMinScoreChange(v), 500);
  }

  onMinScoreChange(v: number): void {
    this.store.setMinScore(v);
    this.fetchGraph();
  }

  ngOnDestroy(): void {
    if (this.minScoreTimer) clearTimeout(this.minScoreTimer);
  }

  onLimitChange(v: number): void {
    this.store.setLimit(v);
    this.fetchGraph();
  }

  onAdjustWeights(): void {
    this.infoOpen.set(false);
    this.weightsOpen.update(open => !open);
  }

  onWeightsApply(draft: ReadonlyMap<string, number>): void {
    const merged = new Map(this.store.customWeights() ?? []);
    draft.forEach((w, code) => merged.set(code, w));

    const allDefault = [...merged.entries()].every(
      ([code, w]) => w === this.effectiveDefault(code));
    this.store.setCustomWeights(allDefault ? null : merged);
    this.fetchGraph();
  }

  onWeightsReset(): void {
    this.store.setCustomWeights(null);
    this.fetchGraph();
  }

  private effectiveDefault(code: string): number {
    return COMPONENT_DEFAULTS.get(code) ?? this.defaultWeights().get(code) ?? 0.5;
  }

  /** Breakdown built from the already re-scored payload, so the panel matches the canvas. */
  private localBreakdown(nodeId: number): EdgeBreakdown | null {
    const g = this.graph();
    const centerId = this.centerId();
    if (!g) return null;
    const edge = g.edges.find(e =>
      (e.source === centerId && e.target === nodeId) ||
      (e.source === nodeId && e.target === centerId));
    if (!edge || !edge.components) return null;

    const node = g.nodes.find(n => n.id === nodeId);
    if (!node) return null;

    const detailOf = (id: number): MovieDetail =>
      id === centerId ? g.center : toDetailLike(node);

    return {
      movieA: detailOf(edge.source),
      movieB: detailOf(edge.target),
      totalScore: edge.score,
      crewScore: crewScoreOf(edge),
      components: [...edge.components].sort((a, b) => b.score - a.score)
    };
  }
}

function toWeightMap(roles: RoleWeight[]): ReadonlyMap<string, number> {
  return new Map(roles.map(r => [r.code, r.baseWeight]));
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

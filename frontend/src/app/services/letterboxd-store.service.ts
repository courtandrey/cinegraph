import { Injectable, signal, computed } from '@angular/core';
import { LetterboxdMap, GraphNode, MapEdge, MapFilm, MapCluster } from '../models/movie.model';
import { placeAdded } from '../pages/letterboxd/cluster-map/cluster-geometry';

@Injectable({ providedIn: 'root' })
export class LetterboxdStore {
  private _map = signal<LetterboxdMap | null>(null);
  private _hash = signal<string | null>(null);
  private _inScoreThreshold = signal(0);

  readonly map = this._map.asReadonly();
  readonly hash = this._hash.asReadonly();
  readonly inScoreThreshold = this._inScoreThreshold.asReadonly();
  readonly loaded = computed(() => (this._map()?.nodes.length ?? 0) > 0);

  readonly filmById = computed(() => {
    const out = new Map<number, MapFilm>();
    for (const f of this._map()?.films ?? []) out.set(f.movieId, f);
    return out;
  });

  readonly adjacency = computed(() => {
    const out = new Map<number, MapEdge[]>();
    const push = (id: number, e: MapEdge) => {
      const list = out.get(id);
      if (list) list.push(e);
      else out.set(id, [e]);
    };
    for (const e of this._map()?.edges ?? []) {
      push(e.source, e);
      push(e.target, e);
    }
    return out;
  });

  readonly clusterById = computed(() => {
    const out = new Map<number, MapCluster>();
    for (const c of this._map()?.clusters ?? []) out.set(c.id, c);
    return out;
  });

  private recsScrollKey: string | null = null;
  private recsScrollTop = 0;

  rememberRecsScroll(key: string, top: number): void {
    this.recsScrollKey = key;
    this.recsScrollTop = top;
  }

  recsScroll(key: string): number {
    return this.recsScrollKey === key ? this.recsScrollTop : 0;
  }

  set(hash: string, map: LetterboxdMap): void {
    this._hash.set(hash);
    this._map.set(map);
    this._inScoreThreshold.set(0);
    this.recsScrollKey = null;
    this.recsScrollTop = 0;
  }

  setInScoreThreshold(v: number): void {
    this._inScoreThreshold.set(v);
  }

  merge(nodes: GraphNode[], edges: MapEdge[]): void {
    this._map.update(map => {
      if (!map) return map;
      const have = new Set(map.nodes.map(n => n.id));
      const addNodes = nodes.filter(n => !have.has(n.id));
      const seen = new Set(map.edges.map(e => edgeKey(e.source, e.target)));
      const addEdges = edges.filter(e => !seen.has(edgeKey(e.source, e.target)));
      const allEdges = [...map.edges, ...addEdges];
      const films = [...map.films];
      for (const n of addNodes) films.push(placeAdded(n.id, films, allEdges));
      return { ...map, nodes: [...map.nodes, ...addNodes], edges: allEdges, films };
    });
  }
}

function edgeKey(a: number, b: number): string {
  return a < b ? `${a}-${b}` : `${b}-${a}`;
}

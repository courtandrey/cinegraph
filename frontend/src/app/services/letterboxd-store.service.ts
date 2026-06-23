import { Injectable, signal, computed } from '@angular/core';
import { LetterboxdGraph, GraphNode, GraphEdge } from '../models/movie.model';

@Injectable({ providedIn: 'root' })
export class LetterboxdStore {
  private _graphs = signal<LetterboxdGraph[]>([]);
  private _index = signal(0);
  private _hash = signal<string | null>(null);
  private _inScoreThreshold = signal(0);

  readonly graphs = this._graphs.asReadonly();
  readonly index = this._index.asReadonly();
  readonly hash = this._hash.asReadonly();
  readonly inScoreThreshold = this._inScoreThreshold.asReadonly();
  readonly count = computed(() => this._graphs().length);
  readonly current = computed(() => this._graphs()[this._index()] ?? null);

  set(hash: string, graphs: LetterboxdGraph[]): void {
    this._hash.set(hash);
    this._graphs.set(graphs);
    this._index.set(0);
    this._inScoreThreshold.set(0);
  }

  setInScoreThreshold(v: number): void {
    this._inScoreThreshold.set(v);
  }

  setIndex(i: number): void {
    if (i >= 0 && i < this._graphs().length) this._index.set(i);
  }

  graphIndexOf(movieId: number): number {
    return this._graphs().findIndex(g => g.nodes.some(n => n.id === movieId));
  }

  graphIndexByCenter(centerId: number): number {
    return this._graphs().findIndex(g => g.centerId === centerId);
  }

  mergeIntoCurrent(node: GraphNode, edges: GraphEdge[]): void {
    this._graphs.update(graphs => {
      const i = this._index();
      const g = graphs[i];
      if (!g) return graphs;
      const nodes = g.nodes.some(n => n.id === node.id) ? g.nodes : [...g.nodes, node];
      const seen = new Set(g.edges.map(e => `${e.source}-${e.target}`));
      const added = edges.filter(e =>
        !seen.has(`${e.source}-${e.target}`) && !seen.has(`${e.target}-${e.source}`));
      const next = [...graphs];
      next[i] = { ...g, nodes, edges: [...g.edges, ...added] };
      return next;
    });
  }

  next(): void {
    this._index.update(i => Math.min(i + 1, this._graphs().length - 1));
  }

  prev(): void {
    this._index.update(i => Math.max(i - 1, 0));
  }
}

import { Injectable, signal } from '@angular/core';
import { MovieSummary } from '../models/movie.model';

@Injectable({ providedIn: 'root' })
export class GraphStore {
  private _visitedCenters = signal<MovieSummary[]>([]);
  readonly visitedCenters = this._visitedCenters.asReadonly();

  static readonly DEFAULT_LIMIT = 25;

  private _minScore = signal(0);
  private _limit = signal(GraphStore.DEFAULT_LIMIT);
  readonly minScore = this._minScore.asReadonly();
  readonly limit = this._limit.asReadonly();

  private _customWeights = signal<ReadonlyMap<string, number> | null>(null);
  readonly customWeights = this._customWeights.asReadonly();

  setCustomWeights(weights: ReadonlyMap<string, number> | null): void {
    this._customWeights.set(weights);
  }

  addVisited(movie: MovieSummary): void {
    this._visitedCenters.update(centers => {
      const filtered = centers.filter(c => c.id !== movie.id);
      return [...filtered, movie].slice(-8);
    });
  }

  setMinScore(v: number): void { this._minScore.set(v); }
  setLimit(v: number): void { this._limit.set(v); }

  resetLimit(): void { this._limit.set(GraphStore.DEFAULT_LIMIT); }
}

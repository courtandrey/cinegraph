import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MovieSummary, MovieDetail, GraphPayload, EdgeBreakdown, RoleWeight } from '../models/movie.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class MovieApiService {
  private http = inject(HttpClient);
  private readonly base = environment.apiBase;

  search(q: string, limit = 10) {
    return this.http.get<MovieSummary[]>(`${this.base}/movies/search`, {
      params: { q, limit: limit.toString() }
    });
  }

  getMovie(id: number) {
    return this.http.get<MovieDetail>(`${this.base}/movies/${id}`);
  }

  getGraph(id: number, minScore = 12, limit = 40) {
    return this.http.get<GraphPayload>(`${this.base}/movies/${id}/graph`, {
      params: { minScore: minScore.toString(), limit: limit.toString() }
    });
  }

  reweightGraph(id: number, limit: number, weights: ReadonlyMap<string, number>) {
    return this.http.post<GraphPayload>(`${this.base}/movies/${id}/reweight`, {
      limit,
      weights: Object.fromEntries(weights)
    });
  }

  getRoles() {
    return this.http.get<RoleWeight[]>(`${this.base}/roles`);
  }

  getEdge(a: number, b: number) {
    const [lo, hi] = a < b ? [a, b] : [b, a];
    return this.http.get<EdgeBreakdown>(`${this.base}/edges/${lo}/${hi}`);
  }
}

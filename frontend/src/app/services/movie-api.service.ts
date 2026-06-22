import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MovieSummary, MovieDetail, GraphPayload, EdgeBreakdown, RoleWeight, LetterboxdGraph, LetterboxdUploadResponse } from '../models/movie.model';
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

  reweightGraph(id: number, limit: number, weights: ReadonlyMap<string, number>, minScore: number) {
    return this.http.post<GraphPayload>(`${this.base}/movies/${id}/reweight`, {
      limit,
      weights: Object.fromEntries(weights),
      minScore
    });
  }

  getRoles() {
    return this.http.get<RoleWeight[]>(`${this.base}/roles`);
  }

  getEdge(a: number, b: number) {
    const [lo, hi] = a < b ? [a, b] : [b, a];
    return this.http.get<EdgeBreakdown>(`${this.base}/edges/${lo}/${hi}`);
  }

  uploadLetterboxd(file: File) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<LetterboxdUploadResponse>(`${this.base}/letterboxd/graphs`, form);
  }

  letterboxdOverview(hash: string) {
    return this.http.get<LetterboxdGraph[]>(`${this.base}/letterboxd/${hash}/graphs`);
  }

  letterboxdSearch(hash: string, q: string, limit = 10) {
    return this.http.get<MovieSummary[]>(`${this.base}/letterboxd/${hash}/search`, {
      params: { q, limit: limit.toString() }
    });
  }

  letterboxdRecenter(hash: string, movieId: number, minScore = 12, limit = 40) {
    return this.http.post<GraphPayload>(`${this.base}/letterboxd/recenter`, {
      hash, movieId, minScore, limit
    });
  }

  letterboxdReweight(hash: string, movieId: number, limit: number,
                     weights: ReadonlyMap<string, number>, minScore: number) {
    return this.http.post<GraphPayload>(`${this.base}/letterboxd/reweight`, {
      hash, movieId, limit, weights: Object.fromEntries(weights), minScore
    });
  }
}

import { MapEdge, MapFilm } from '../../../models/movie.model';

const POSTER_W = 64;
const POSTER_H = 96;
const LABEL_H = 20;
const SPIRAL_STEP = 40;
const SPIRAL_TURNS = 400;

export function placeAdded(movieId: number, films: MapFilm[], edges: MapEdge[]): MapFilm {
  const byId = new Map(films.map(f => [f.movieId, f]));
  const neighbours = edges
    .filter(e => e.source === movieId || e.target === movieId)
    .map(e => byId.get(e.source === movieId ? e.target : e.source))
    .filter((f): f is MapFilm => !!f);
  const anchor = neighbours.length
    ? { x: mean(neighbours.map(f => f.x)), y: mean(neighbours.map(f => f.y)) }
    : outsideAll(films);
  const spot = firstFree(anchor, films);
  return {
    movieId,
    clusterId: 0,
    componentId: neighbours[0]?.componentId ?? 0,
    placement: 'ADDED',
    cohesion: 0,
    linksInside: 0,
    linksTotal: neighbours.length,
    shares: [],
    x: spot.x,
    y: spot.y
  };
}

function firstFree(origin: { x: number; y: number }, films: MapFilm[]): { x: number; y: number } {
  for (let i = 0; i < SPIRAL_TURNS; i++) {
    const r = SPIRAL_STEP * Math.sqrt(i);
    const a = i * 2.3999632;
    const p = { x: origin.x + r * Math.cos(a), y: origin.y + r * Math.sin(a) };
    if (films.every(f => Math.abs(f.x - p.x) >= POSTER_W + 8 || Math.abs(f.y - p.y) >= POSTER_H + LABEL_H)) return p;
  }
  return outsideAll(films);
}

function outsideAll(films: MapFilm[]): { x: number; y: number } {
  if (!films.length) return { x: 0, y: 0 };
  return { x: Math.min(...films.map(f => f.x)) - 3 * POSTER_W, y: Math.min(...films.map(f => f.y)) };
}

function mean(xs: number[]): number {
  return xs.reduce((a, b) => a + b, 0) / xs.length;
}

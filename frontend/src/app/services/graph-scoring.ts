import { GraphPayload, GraphEdge } from '../models/movie.model';

export const MAX_WEIGHT = 20;

export const COMPONENT_DEFAULTS: ReadonlyMap<string, number> = new Map([
  ['SHARED_GENRES', 2.0],
  ['SHARED_KEYWORDS', 0.5],
  ['RELEASE_PROXIMITY', 4.0]
]);

export function isComponentCode(code: string): boolean {
  return COMPONENT_DEFAULTS.has(code);
}

export function crewScoreOf(edge: GraphEdge): number {
  if (!edge.components) return 0;
  return edge.components
    .filter(c => c.type === 'SHARED_PERSON')
    .reduce((sum, c) => sum + c.score, 0);
}

export function rolesInGraph(graph: GraphPayload): Map<string, number> {
  const counts = new Map<string, number>();
  const bump = (code: string | undefined) => {
    if (!code) return;
    counts.set(code, (counts.get(code) ?? 0) + 1);
  };
  for (const e of graph.edges) {
    for (const c of e.components ?? []) {
      if (c.type === 'SHARED_PERSON') {
        bump(c.roleA);
        if (c.roleB !== c.roleA) bump(c.roleB);
      } else if (COMPONENT_DEFAULTS.has(c.type)) {
        bump(c.type);
      }
    }
  }
  return counts;
}

export function roleLabel(code: string): string {
  switch (code) {
    case 'DOP': return 'Director of Photography';
    case 'SHARED_GENRES': return 'Shared genres';
    case 'SHARED_KEYWORDS': return 'Shared keywords';
    case 'RELEASE_PROXIMITY': return 'Release proximity';
    default:
      return code
        .toLowerCase()
        .split('_')
        .map(w => w.charAt(0).toUpperCase() + w.slice(1))
        .join(' ');
  }
}

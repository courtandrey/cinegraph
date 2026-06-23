export interface MovieSummary {
  id: number;
  title: string;
  year: number | null;
  posterPath: string | null;
}

export interface Genre {
  id: number;
  name: string;
}

export interface MovieDetail extends MovieSummary {
  originalTitle: string;
  releaseDate: string | null;
  overview: string;
  runtime: number | null;
  genres: Genre[];
  countries: string[];
  voteAverage: number;
}

export interface GraphNode {
  id: number;
  title: string;
  year: number | null;
  posterPath: string | null;
  inScore: number;
}

export interface GraphEdge {
  source: number;
  target: number;
  score: number;
  topReason: string;
  components?: EdgeComponent[];
}

export interface RoleWeight {
  code: string;
  baseWeight: number;
}

export interface GraphPayload {
  center: MovieDetail;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface LetterboxdGraph {
  centerId: number;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface LetterboxdUploadResponse {
  hash: string;
  graphs: LetterboxdGraph[];
}

export interface LetterboxdSearchResult {
  id: number;
  title: string;
  year: number | null;
  posterPath: string | null;
  graphId: number | null;
}

export interface LetterboxdAttachment {
  node: GraphNode;
  edges: GraphEdge[];
}

export interface PathResult {
  found: boolean;
  reason: string | null;
  hops: number;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface EdgeComponent {
  type: 'SHARED_PERSON' | 'SHARED_GENRES' | 'SHARED_KEYWORDS' | 'RELEASE_PROXIMITY';
  score: number;
  personId?: number;
  name?: string;
  roleA?: string;
  roleB?: string;
  sameRole?: boolean;
  genreIds?: number[];
  names?: string[];
  count?: number;
  sampleNames?: string[];
  deltaYears?: number;
}

export interface EdgeBreakdown {
  movieA: MovieDetail;
  movieB: MovieDetail;
  totalScore: number;
  crewScore: number;
  components: EdgeComponent[];
}

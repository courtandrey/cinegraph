import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MovieApiService } from '../../services/movie-api.service';
import { SeoService } from '../../services/seo.service';
import { GraphNode, PathResult } from '../../models/movie.model';

const POSTER_BASE = 'https://image.tmdb.org/t/p';

@Component({
  selector: 'app-path-page',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './path-page.component.html',
  styleUrl: './path-page.component.scss'
})
export class PathPageComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private api = inject(MovieApiService);
  private seo = inject(SeoService);

  readonly loading = signal(true);
  readonly result = signal<PathResult | null>(null);

  ngOnInit(): void {
    this.route.params.subscribe(params => {
      const from = Number(params['from']);
      const to = Number(params['to']);
      if (!from || !to || from === to) {
        this.router.navigate(['/']);
        return;
      }
      this.load(from, to);
    });
  }

  private load(from: number, to: number): void {
    this.loading.set(true);
    this.result.set(null);
    this.api.findPath(from, to).subscribe({
      next: res => {
        this.result.set(res);
        this.loading.set(false);
        this.applySeo(from, to, res);
      },
      error: () => {
        this.result.set({ found: false, reason: 'error', hops: 0, nodes: [], edges: [] });
        this.loading.set(false);
      }
    });
  }

  private applySeo(from: number, to: number, res: PathResult): void {
    if (!res.found || res.nodes.length < 2) {
      this.seo.apply({
        title: 'Film path not found | CineGraph',
        description: 'No connection path found between these films.',
        noindex: true
      });
      return;
    }
    const first = res.nodes[0];
    const last = res.nodes[res.nodes.length - 1];
    const chain = res.nodes.map(n => n.title).join(' → ');
    this.seo.apply({
      title: `How ${first.title} connects to ${last.title} — movie path | CineGraph`,
      description: `The shortest path from ${withYear(first)} to ${withYear(last)} through shared crew and cast: ${chain}.`,
      canonicalPath: `/path/${from}/${to}`,
      image: first.posterPath ? this.posterUrl(first.posterPath, 'w500') : null
    });
  }

  posterUrl(path: string, size: 'w342' | 'w500'): string {
    return `${POSTER_BASE}/${size}${path}`;
  }

  reasonText(reason: string | null): string {
    switch (reason) {
      case 'loading': return 'Graph is reloading. Please try again in a moment.';
      case 'unreachable': return 'No path — these films aren’t connected.';
      case 'budget': return 'These films are too far apart to connect quickly.';
      case 'not_found': return 'Film not found.';
      default: return 'Could not find a path.';
    }
  }
}

function withYear(node: GraphNode): string {
  return node.year ? `${node.title} (${node.year})` : node.title;
}

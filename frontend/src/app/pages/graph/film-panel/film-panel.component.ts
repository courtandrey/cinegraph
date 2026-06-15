import { Component, Input, Output, EventEmitter, computed, signal } from '@angular/core';
import { GraphPayload, EdgeBreakdown, EdgeComponent, MovieDetail } from '../../../models/movie.model';

const POSTER_W342 = 'https://image.tmdb.org/t/p/w342';
const POSTER_W92  = 'https://image.tmdb.org/t/p/w92';

@Component({
  selector: 'app-film-panel',
  standalone: true,
  templateUrl: './film-panel.component.html',
  styleUrl: './film-panel.component.scss'
})
export class FilmPanelComponent {
  @Input() graph: GraphPayload | null = null;
  @Input() breakdown: EdgeBreakdown | null = null;
  @Input() selectedNodeId: number | null = null;
  @Input() weightsActive = false;

  @Output() reCenter = new EventEmitter<number>();
  @Output() backToCenter = new EventEmitter<void>();
  @Output() adjustWeights = new EventEmitter<void>();

  get center(): MovieDetail | null {
    return this.graph?.center ?? null;
  }

  get selectedMovie(): MovieDetail | null {
    if (this.selectedNodeId == null || !this.graph) return null;
    if (this.breakdownReady) {
      const centerId = this.graph.center.id;
      return this.breakdown!.movieA.id !== centerId
        ? this.breakdown!.movieA
        : this.breakdown!.movieB;
    }
    const node = this.graph.nodes.find(n => n.id === this.selectedNodeId);
    return node
      ? {
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
        }
      : null;
  }

  /** True only when the loaded breakdown actually belongs to the selected node. */
  get breakdownReady(): boolean {
    if (!this.breakdown || this.selectedNodeId == null || !this.graph) return false;
    const centerId = this.graph.center.id;
    const other = this.breakdown.movieA.id !== centerId
      ? this.breakdown.movieA
      : this.breakdown.movieB;
    return other.id === this.selectedNodeId;
  }

  posterW342(path: string | null): string | null {
    return path ? `${POSTER_W342}${path}` : null;
  }

  posterW92(path: string | null): string | null {
    return path ? `${POSTER_W92}${path}` : null;
  }

  componentLabel(comp: EdgeComponent): string {
    switch (comp.type) {
      case 'SHARED_PERSON':
        return this.personLabel(comp);
      case 'SHARED_GENRES':
        return `Shared genres: ${(comp.names ?? []).join(', ')}`;
      case 'SHARED_KEYWORDS': {
        const sample = (comp.sampleNames ?? []).slice(0, 3);
        return `${comp.count} shared keyword${comp.count === 1 ? '' : 's'}${sample.length ? ` (${sample.join(', ')})` : ''}`;
      }
      case 'RELEASE_PROXIMITY':
        return `Released ${comp.deltaYears} year${comp.deltaYears === 1 ? '' : 's'} apart`;
      default:
        return comp.type;
    }
  }

  private personLabel(comp: EdgeComponent): string {
    const name = comp.name ?? '';
    if (comp.sameRole) {
      const roleLabel = this.sameRoleLabel(comp.roleA ?? '');
      return `${roleLabel} — ${name}`;
    }
    const a = this.prettyRole(comp.roleA ?? '');
    const b = this.prettyRole(comp.roleB ?? '');
    return `${name} (${a} → ${b})`;
  }

  private sameRoleLabel(role: string): string {
    const map: Record<string, string> = {
      DIRECTOR: 'Same director',
      WRITER: 'Same writer',
      DOP: 'Same cinematographer',
      EDITOR: 'Same editor',
      COMPOSER: 'Same composer',
      PRODUCER: 'Same producer',
      PRODUCTION_DESIGNER: 'Same production designer',
      COSTUME_DESIGNER: 'Same costume designer',
      EXEC_PRODUCER: 'Same executive producer',
      CAST_LEAD: 'Same lead',
      CAST_SUPPORT: 'Same supporting actor',
    };
    return map[role] ?? `Same ${this.prettyRole(role)}`;
  }

  private prettyRole(role: string): string {
    return role
      .toLowerCase()
      .replace(/_/g, ' ')
      .replace(/^cast /, '');
  }
}

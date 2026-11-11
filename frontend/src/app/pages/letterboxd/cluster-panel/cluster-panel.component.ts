import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { GraphNode, MapCluster } from '../../../models/movie.model';
import { genreColor } from '../cluster-map/cluster-colors';

export interface NeighbourCluster {
  id: number;
  label: string;
  color: string;
}

const ROLE_NAMES: Record<string, string> = {
  DIRECTOR: 'Director',
  WRITER: 'Writer',
  DOP: 'Cinematographer',
  EDITOR: 'Editor',
  PRODUCER: 'Producer',
  PRODUCTION_DESIGNER: 'Production designer',
  COMPOSER: 'Composer',
  COSTUME_DESIGNER: 'Costume designer',
  EXEC_PRODUCER: 'Executive producer',
  CAST_LEAD: 'Lead cast',
  CAST_SUPPORT: 'Supporting cast',
  CAST_MINOR: 'Cast',
  CREW_OTHER: 'Crew',
  CREW: 'Crew'
};

@Component({
  selector: 'app-cluster-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cluster-panel.component.html',
  styleUrl: './cluster-panel.component.scss'
})
export class ClusterPanelComponent {
  private readonly current = signal<MapCluster | null>(null);

  @Input({ required: true }) set cluster(c: MapCluster) { this.current.set(c); }
  get cluster(): MapCluster { return this.current()!; }

  @Input({ required: true }) label = '';
  @Input({ required: true }) labels!: Map<number, string>;
  @Input({ required: true }) colors!: Map<number, string>;
  @Input() hub: GraphNode | null = null;
  @Input() bridges = 0;
  @Input() neighbours: NeighbourCluster[] = [];

  @Output() filmTap = new EventEmitter<number>();
  @Output() clusterTap = new EventEmitter<number>();

  readonly segments = computed(() => {
    const c = this.current();
    if (!c) return [];
    const total = c.genres.reduce((a, g) => a + g.films, 0) || 1;
    return c.genres.map(g => ({ ...g, share: g.films / total, color: genreColor(g.genreId) }));
  });

  roleName(code: string): string {
    return ROLE_NAMES[code] ?? code.toLowerCase().replace(/_/g, ' ').replace(/^./, ch => ch.toUpperCase());
  }

  percent(v: number): string {
    return `${Math.round(v * 100)}%`;
  }
}

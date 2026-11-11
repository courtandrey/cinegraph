import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { MapCluster, MapFilm } from '../../../models/movie.model';
import { NEUTRAL_COLOR } from '../cluster-map/cluster-colors';

interface ShareRow {
  clusterId: number;
  label: string;
  color: string;
  share: number;
  links: number;
  home: boolean;
}

const LEGEND_ROWS = 6;

@Component({
  selector: 'app-film-shares',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './film-shares.component.html',
  styleUrl: './film-shares.component.scss'
})
export class FilmSharesComponent {
  private readonly current = signal<MapFilm | null>(null);
  private readonly labelOf = signal<Map<number, string>>(new Map());
  private readonly colorOf = signal<Map<number, string>>(new Map());
  private readonly clusterOf = signal<Map<number, MapCluster>>(new Map());

  @Input({ required: true }) set film(f: MapFilm) {
    this.current.set(f);
    this.hovered.set(null);
    this.expanded.set(false);
  }
  @Input({ required: true }) set labels(m: Map<number, string>) { this.labelOf.set(m); }
  @Input({ required: true }) set colors(m: Map<number, string>) { this.colorOf.set(m); }
  @Input({ required: true }) set clusters(m: Map<number, MapCluster>) { this.clusterOf.set(m); }

  @Output() clusterTap = new EventEmitter<number>();

  readonly hovered = signal<number | null>(null);
  readonly expanded = signal(false);

  readonly rows = computed<ShareRow[]>(() => {
    const f = this.current();
    if (!f) return [];
    return f.shares.map(s => ({
      clusterId: s.clusterId,
      label: this.labelOf().get(s.clusterId) || 'Small group',
      color: this.colorOf().get(s.clusterId) ?? NEUTRAL_COLOR,
      share: s.share,
      links: s.links,
      home: s.clusterId === f.clusterId
    }));
  });

  readonly legend = computed<ShareRow[]>(() => {
    const rows = this.rows();
    if (this.expanded()) return rows;
    const top = rows.slice(0, LEGEND_ROWS);
    const extra = rows.find(r => r.clusterId === this.hovered() && !top.includes(r));
    return extra ? [...top, extra] : top;
  });

  readonly hiddenCount = computed(() => Math.max(0, this.rows().length - this.legend().length));
  readonly totalLinks = computed(() => this.current()?.linksTotal ?? 0);
  readonly bridge = computed(() => this.current()?.placement === 'BRIDGE');

  percent(v: number): string {
    const p = v * 100;
    return p > 0 && p < 1 ? '<1%' : `${Math.round(p)}%`;
  }

  isOpenable(clusterId: number): boolean {
    return this.clusterOf().has(clusterId);
  }
}

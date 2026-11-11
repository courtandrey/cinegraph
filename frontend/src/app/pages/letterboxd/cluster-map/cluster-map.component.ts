import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  NgZone,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild
} from '@angular/core';
import cytoscape, { Collection, Core, ElementDefinition, NodeCollection } from 'cytoscape';
import { ClusterLink, GraphNode, LetterboxdMap, MapCluster, MapEdge, MapFilm } from '../../../models/movie.model';
import { NEUTRAL_COLOR } from './cluster-colors';

const POSTER_W185 = 'https://image.tmdb.org/t/p/w185';
const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';

const OVERVIEW_ZOOM = 0.2;
const PHONE_MAX_WIDTH = 820;
const FOCUS_ZOOM = 1.2;
const LARGE_NODE_COUNT = 150;
const OVERVIEW_LABEL = { perDiameter: 1 / 7, widthPerDiameter: 0.9, charWidth: 0.62, lineChars: 11, minPx: 6 };
const DETAIL_LABEL = { size: 30, width: 520, minPx: 6 };
const DISC_BORDER = { overview: 18, detail: 4 };
const LINK_WIDTH = { overview: [10, 70], detail: [2, 12] };
const TOOLTIP_OFFSET = { x: 12, y: -34 };
const BACKGROUND = '#14181c';
const OVERVIEW_FILL = { cluster: 0.4, group: 0.12 };
const VEIL_ID = 'veil';
const Z = { disc: 0, link: 1, film: 10, veil: 11, litDisc: 12, edge: 13, lit: 14, overviewDisc: 15, pathEdge: 19, path: 20 };

export interface ClusterPair {
  a: number;
  b: number;
}

@Component({
  selector: 'app-cluster-map',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cluster-map.component.html',
  styleUrl: './cluster-map.component.scss'
})
export class ClusterMapComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild('container') containerRef!: ElementRef<HTMLDivElement>;
  @ViewChild('tooltip') tooltipRef!: ElementRef<HTMLDivElement>;
  @ViewChild('tooltipText') tooltipTextRef!: ElementRef<HTMLSpanElement>;
  @ViewChild('tooltipShares') tooltipSharesRef!: ElementRef<HTMLSpanElement>;

  @Input({ required: true }) map!: LetterboxdMap;
  @Input({ required: true }) adjacency!: Map<number, MapEdge[]>;
  @Input({ required: true }) colors!: Map<number, string>;
  @Input({ required: true }) labels!: Map<number, string>;
  @Input() selectedNodeId: number | null = null;
  @Input() selectedClusterId: number | null = null;
  @Input() selectedLink: ClusterPair | null = null;
  @Input() focus: { id: number } | null = null;
  @Input() focusCluster: { id: number } | null = null;
  @Input() pathNodeIds: number[] | null = null;
  @Input() pathRightInset = 0;

  @Output() nodeTap = new EventEmitter<number>();
  @Output() clusterTap = new EventEmitter<number>();
  @Output() linkTap = new EventEmitter<ClusterPair>();
  @Output() clearSelection = new EventEmitter<void>();

  private cy: Core | null = null;
  private filmById = new Map<number, MapFilm>();
  private overview: boolean | null = null;
  private zoomRaf = 0;
  private shownEdges: Collection | null = null;
  private pathEdges: Collection | null = null;
  private marked: Collection | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private resizeRaf = 0;
  private lastW = 0;
  private lastH = 0;

  constructor(private ngZone: NgZone) {}

  ngAfterViewInit(): void {
    this.init();
    this.observeResize();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.cy) return;
    const restyled = !!(changes['colors'] || changes['labels']);
    if (changes['map'] || restyled) this.rebuild(restyled);
    if (changes['map'] || changes['adjacency'] || changes['selectedNodeId']
      || changes['selectedClusterId'] || changes['selectedLink']) this.applySelection();
    if (changes['focus'] && this.focus) this.focusFilm(this.focus.id);
    if (changes['focusCluster'] && this.focusCluster) this.fitClusters([this.focusCluster.id]);
    if (changes['selectedLink'] && this.selectedLink) this.fitClusters([this.selectedLink.a, this.selectedLink.b]);
    if (changes['pathNodeIds'] || changes['map']) this.applyPath(!!changes['pathNodeIds']);
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    if (this.resizeRaf) cancelAnimationFrame(this.resizeRaf);
    if (this.zoomRaf) cancelAnimationFrame(this.zoomRaf);
    this.cy?.destroy();
    this.cy = null;
  }

  hideTooltip(): void {
    this.tooltipRef.nativeElement.hidden = true;
  }

  fitAll(): void {
    this.ngZone.runOutsideAngular(() => {
      this.cy?.stop();
      this.cy?.animate({ fit: { eles: this.overviewTargets(), padding: this.padding() } },
        { duration: 450, easing: 'ease-in-out-cubic' });
    });
  }

  private init(): void {
    this.ngZone.runOutsideAngular(() => {
      const large = this.map.nodes.length > LARGE_NODE_COUNT;
      this.cy = cytoscape({
        container: this.containerRef.nativeElement,
        elements: this.initialElements(),
        style: this.buildStyle(large ? POSTER_W92 : POSTER_W185) as any,
        layout: { name: 'preset', fit: false } as any,
        motionBlur: false,
        pixelRatio: large ? 1 : 'auto',
        minZoom: 0.02,
        maxZoom: 3
      });
      this.bindEvents(this.cy);
      this.cy.fit(this.overviewTargets(), this.padding());
      this.syncZoom();
      this.applySelection();
      this.applyPath(false);
    });
  }

  private bindEvents(cy: Core): void {
    cy.on('tap', 'node.film', evt => this.ngZone.run(() => this.nodeTap.emit(Number(evt.target.id()))));
    cy.on('tap', 'node.disc', evt => {
      const id = evt.target.data('clusterId') as number;
      const zoomOnly = this.overview === true && this.isPhone();
      this.fitClusters([id]);
      if (!zoomOnly) this.ngZone.run(() => this.clusterTap.emit(id));
    });
    cy.on('tap', 'edge.link', evt => {
      const pair = { a: evt.target.data('a') as number, b: evt.target.data('b') as number };
      this.ngZone.run(() => this.linkTap.emit(pair));
    });
    cy.on('tap', evt => {
      if ((evt.target as unknown) === cy) this.ngZone.run(() => this.clearSelection.emit());
    });
    cy.on('zoom', () => {
      if (this.zoomRaf) return;
      this.zoomRaf = requestAnimationFrame(() => {
        this.zoomRaf = 0;
        this.syncZoom();
      });
    });
    cy.on('mouseover', 'node.film', evt => this.showTooltip(
      evt.target.data('tip'), evt.renderedPosition, this.filmById.get(Number(evt.target.id()))));
    cy.on('mouseover', 'node.disc, edge', evt => this.showTooltip(evt.target.data('tip'), evt.renderedPosition));
    cy.on('mousemove', 'node.film, node.disc, edge', evt => this.moveTooltip(evt.renderedPosition));
    cy.on('mouseout', 'node, edge', () => this.hideTooltip());
    cy.on('viewport', () => this.hideTooltip());
  }

  private showTooltip(text: string | undefined, pos: { x: number; y: number }, film?: MapFilm): void {
    if (!text) return;
    this.tooltipTextRef.nativeElement.textContent = text;
    const shares = this.tooltipSharesRef.nativeElement;
    shares.replaceChildren(...(film?.shares ?? []).map(s => {
      const block = document.createElement('span');
      block.style.flexGrow = String(s.share);
      block.style.background = this.colorOf(s.clusterId);
      return block;
    }));
    shares.hidden = !film || film.shares.length === 0;
    this.tooltipRef.nativeElement.hidden = false;
    this.moveTooltip(pos);
  }

  private moveTooltip(pos: { x: number; y: number }): void {
    this.tooltipRef.nativeElement.style.transform =
      `translate(${pos.x + TOOLTIP_OFFSET.x}px, ${pos.y + TOOLTIP_OFFSET.y}px)`;
  }

  private observeResize(): void {
    const el = this.containerRef.nativeElement;
    this.lastW = el.clientWidth;
    this.lastH = el.clientHeight;
    this.ngZone.runOutsideAngular(() => {
      this.resizeObserver = new ResizeObserver(entries => {
        const { width, height } = entries[0].contentRect;
        if (Math.abs(width - this.lastW) < 2 && Math.abs(height - this.lastH) < 2) return;
        this.lastW = width;
        this.lastH = height;
        if (this.resizeRaf) cancelAnimationFrame(this.resizeRaf);
        this.resizeRaf = requestAnimationFrame(() => {
          this.cy?.resize();
          this.cy?.fit(this.overviewTargets(), this.padding());
        });
      });
      this.resizeObserver.observe(el);
    });
  }

  private overviewTargets(): NodeCollection {
    return this.cy!.nodes('.disc');
  }

  private isPhone(): boolean {
    return this.containerRef.nativeElement.clientWidth <= PHONE_MAX_WIDTH;
  }

  private padding(): number {
    return this.containerRef.nativeElement.clientWidth < 640 ? 16 : 48;
  }

  private colorOf(clusterId: number): string {
    return this.colors.get(clusterId) ?? NEUTRAL_COLOR;
  }

  private syncZoom(): void {
    const cy = this.cy;
    if (!cy) return;
    const overview = cy.zoom() < OVERVIEW_ZOOM;
    if (overview === this.overview) return;
    this.overview = overview;
    cy.batch(() => {
      cy.nodes('.disc').union(cy.edges()).toggleClass('ov', overview);
      cy.nodes('.film.core').toggleClass('ov-hidden', overview);
    });
  }

  private initialElements(): ElementDefinition[] {
    this.filmById = new Map(this.map.films.map(f => [f.movieId, f]));
    const maxAffinity = this.maxAffinity();
    return [
      this.veilDef(),
      ...this.map.clusters.map(c => this.discDef(c)),
      ...this.visibleLinks().map(l => this.linkDef(l, maxAffinity)),
      ...this.map.nodes.filter(n => this.filmById.has(n.id)).map(n => this.filmDef(n, this.filmById.get(n.id)!))
    ];
  }

  private rebuild(restyled: boolean): void {
    const cy = this.cy;
    if (!cy) return;
    this.filmById = new Map(this.map.films.map(f => [f.movieId, f]));
    this.ngZone.runOutsideAngular(() => {
      cy.batch(() => {
        const wanted = new Set<string>();
        for (const n of this.map.nodes) {
          const film = this.filmById.get(n.id);
          if (!film) continue;
          wanted.add(String(n.id));
          const existing = cy.$id(String(n.id));
          if (existing.empty()) {
            cy.add(this.filmDef(n, film));
          } else {
            existing.removeClass('filtered');
            if (restyled) existing.data('color', this.colorOf(film.clusterId));
          }
        }
        cy.nodes('.film').filter(n => !wanted.has(n.id())).addClass('filtered');
        if (restyled) {
          for (const c of this.map.clusters) cy.$id(`c${c.id}`).data(this.discDef(c).data);
        }
      });
    });
  }

  private maxAffinity(): number {
    return Math.max(1e-9, ...this.map.links.map(l => l.affinity));
  }

  private visibleLinks(): ClusterLink[] {
    const ids = new Set(this.map.clusters.map(c => c.id));
    return this.map.links.filter(l => ids.has(l.source) && ids.has(l.target));
  }

  private veilDef(): ElementDefinition {
    const xs = this.map.clusters.flatMap(c => [c.x - c.radius, c.x + c.radius]);
    const ys = this.map.clusters.flatMap(c => [c.y - c.radius, c.y + c.radius]);
    const [x1, x2, y1, y2] = [Math.min(0, ...xs), Math.max(0, ...xs), Math.min(0, ...ys), Math.max(0, ...ys)];
    return {
      group: 'nodes',
      data: { id: VEIL_ID, w: 3 * (x2 - x1) + 4000, h: 3 * (y2 - y1) + 4000 },
      position: { x: (x1 + x2) / 2, y: (y1 + y2) / 2 },
      classes: 'veil'
    };
  }

  private discDef(c: MapCluster): ElementDefinition {
    const label = this.labels.get(c.id) ?? '';
    const color = this.colorOf(c.id);
    return {
      group: 'nodes',
      data: {
        id: `c${c.id}`, clusterId: c.id, size: 2 * c.radius, color,
        overviewLabel: c.kind === 'CLUSTER' ? overviewLines(label, c.size).join('\n') : '',
        font: overviewFont(overviewLines(label, c.size), 2 * c.radius),
        fill: blend(color, BACKGROUND, c.kind === 'CLUSTER' ? OVERVIEW_FILL.cluster : OVERVIEW_FILL.group),
        label: c.kind === 'CLUSTER' ? `${label}\n${c.size} films` : '',
        tip: `${label || 'Small group'} · ${c.size} films`
      },
      position: { x: c.x, y: c.y },
      classes: c.kind === 'CLUSTER' ? 'disc' : 'disc group'
    };
  }

  private linkDef(l: ClusterLink, maxAffinity: number): ElementDefinition {
    const name = (id: number) => this.labels.get(id) || 'Small group';
    return {
      group: 'edges',
      data: {
        id: `l${l.source}_${l.target}`, source: `c${l.source}`, target: `c${l.target}`,
        a: l.source, b: l.target, strength: l.affinity / maxAffinity,
        tip: `${name(l.source)} ↔ ${name(l.target)} · ${l.edges} connections`
      },
      classes: 'link'
    };
  }

  private filmDef(n: GraphNode, f: MapFilm): ElementDefinition {
    return {
      group: 'nodes',
      data: { id: String(n.id), label: n.title, tip: n.title, posterPath: n.posterPath, color: this.colorOf(f.clusterId) },
      position: { x: f.x, y: f.y },
      classes: `film ${f.placement.toLowerCase()}${this.overview && f.placement === 'CORE' ? ' ov-hidden' : ''}`
    };
  }

  private edgeDef(e: MapEdge): ElementDefinition {
    const a = this.filmById.get(e.source);
    const b = this.filmById.get(e.target);
    const intra = !!a && !!b && a.clusterId === b.clusterId && a.clusterId !== 0;
    return {
      group: 'edges',
      data: {
        id: `e_${e.source}_${e.target}`, source: String(e.source), target: String(e.target),
        color: intra ? this.colorOf(a!.clusterId) : '#9ab',
        tip: e.topReason ? `${e.topReason} · ${e.score.toFixed(1)}` : e.score.toFixed(1)
      },
      classes: this.overview ? 'film-edge ov' : 'film-edge'
    };
  }

  private isShown(id: number): boolean {
    const node = this.cy!.$id(String(id));
    return node.nonempty() && !node.hasClass('filtered');
  }

  private applySelection(): void {
    const cy = this.cy;
    if (!cy) return;
    this.ngZone.runOutsideAngular(() => cy.batch(() => {
      this.shownEdges?.remove();
      this.shownEdges = null;
      this.marked?.removeClass('selected lit focused on');
      this.marked = this.selectFilm(cy) ?? this.selectCluster(cy) ?? this.selectLink(cy);
      this.marked?.merge(cy.$id(VEIL_ID).addClass('on'));
    }));
  }

  private selectFilm(cy: Core): Collection | null {
    const id = this.selectedNodeId;
    if (id == null || !this.isShown(id)) return null;
    const edges = (this.adjacency.get(id) ?? [])
      .filter(e => this.isShown(e.source === id ? e.target : e.source));
    this.shownEdges = cy.add(edges.map(e => this.edgeDef(e)));
    const lit = cy.collection();
    for (const e of edges) lit.merge(cy.$id(String(e.source === id ? e.target : e.source)));
    return lit.addClass('lit').merge(cy.$id(String(id)).addClass('selected lit'));
  }

  private selectCluster(cy: Core): Collection | null {
    const id = this.selectedClusterId;
    if (id == null) return null;
    return this.spotlight(cy, [id]);
  }

  private selectLink(cy: Core): Collection | null {
    const pair = this.selectedLink;
    if (!pair) return null;
    const edges: MapEdge[] = [];
    for (const f of this.map.films) {
      if (f.clusterId !== pair.a || !this.isShown(f.movieId)) continue;
      for (const e of this.adjacency.get(f.movieId) ?? []) {
        const other = e.source === f.movieId ? e.target : e.source;
        if (this.filmById.get(other)?.clusterId === pair.b && this.isShown(other)) edges.push(e);
      }
    }
    this.shownEdges = cy.add(edges.map(e => this.edgeDef(e)));
    return this.spotlight(cy, [pair.a, pair.b]);
  }

  private spotlight(cy: Core, clusterIds: number[]): Collection {
    const lit = cy.collection();
    for (const f of this.map.films) {
      if (clusterIds.includes(f.clusterId)) lit.merge(cy.$id(String(f.movieId)));
    }
    lit.addClass('lit');
    for (const id of clusterIds) lit.merge(cy.$id(`c${id}`).addClass('focused'));
    return lit;
  }

  private applyPath(animate: boolean): void {
    const cy = this.cy;
    if (!cy) return;
    const ids = this.pathNodeIds;
    this.ngZone.runOutsideAngular(() => {
      let nodes = cy.collection();
      cy.batch(() => {
        this.pathEdges?.remove();
        this.pathEdges = null;
        cy.nodes('.path-node').removeClass('path-node');
        if (!ids?.length) return;
        for (const id of ids) nodes = nodes.union(cy.$id(String(id)).addClass('path-node'));
        const hops: ElementDefinition[] = [];
        for (let i = 0; i + 1 < ids.length; i++) {
          hops.push({
            group: 'edges',
            data: { id: `p_${ids[i]}_${ids[i + 1]}`, source: String(ids[i]), target: String(ids[i + 1]) },
            classes: this.overview ? 'path-edge ov' : 'path-edge'
          });
        }
        this.pathEdges = cy.add(hops.filter(h => cy.$id(h.data['source']).nonempty() && cy.$id(h.data['target']).nonempty()));
      });
      if (animate && nodes.nonempty()) {
        cy.stop();
        cy.animate(this.viewportFor(nodes), { duration: 450, easing: 'ease-in-out-cubic' });
      }
    });
  }

  private focusFilm(id: number): void {
    const cy = this.cy;
    if (!cy) return;
    const node = cy.$id(String(id));
    if (node.empty()) return;
    const el = this.containerRef.nativeElement;
    const p = node.position();
    const visibleWidth = el.clientWidth - Math.max(0, this.pathRightInset);
    this.ngZone.runOutsideAngular(() => {
      cy.stop();
      cy.animate({
        zoom: FOCUS_ZOOM,
        pan: { x: visibleWidth / 2 - FOCUS_ZOOM * p.x, y: el.clientHeight / 2 - FOCUS_ZOOM * p.y }
      }, { duration: 450, easing: 'ease-in-out-cubic' });
    });
  }

  private fitClusters(ids: number[]): void {
    const cy = this.cy;
    if (!cy) return;
    const discs = cy.collection();
    for (const id of ids) discs.merge(cy.$id(`c${id}`));
    if (discs.empty()) return;
    this.ngZone.runOutsideAngular(() => {
      cy.stop();
      cy.animate(this.viewportFor(discs), { duration: 450, easing: 'ease-in-out-cubic' });
    });
  }

  private viewportFor(eles: cytoscape.Collection): { zoom: number; pan: { x: number; y: number } } {
    const cy = this.cy!;
    const el = this.containerRef.nativeElement;
    const pad = this.padding();
    const inset = Math.max(0, this.pathRightInset);
    const availW = Math.max(60, el.clientWidth - inset - 2 * pad);
    const availH = Math.max(60, el.clientHeight - 2 * pad);
    const bb = eles.boundingBox({ includeLabels: false });
    const zoom = Math.max(cy.minZoom(), Math.min(1.5, availW / bb.w, availH / bb.h));
    return {
      zoom,
      pan: {
        x: (el.clientWidth - inset) / 2 - zoom * (bb.x1 + bb.w / 2),
        y: el.clientHeight / 2 - zoom * (bb.y1 + bb.h / 2)
      }
    };
  }

  private buildStyle(posterBase: string): object[] {
    return [
      { selector: 'node, edge', style: { 'z-index-compare': 'manual' } },
      {
        selector: 'node.veil',
        style: {
          shape: 'rectangle', width: 'data(w)', height: 'data(h)', display: 'none', events: 'no',
          'background-color': BACKGROUND, 'background-opacity': 0.82, 'z-index': Z.veil
        }
      },
      { selector: 'node.veil.on', style: { display: 'element' } },
      {
        selector: 'node.disc',
        style: {
          shape: 'ellipse',
          width: 'data(size)',
          height: 'data(size)',
          'background-color': 'data(color)',
          'background-opacity': 0.09,
          'border-color': 'data(color)',
          'border-opacity': 0.55,
          'border-width': DISC_BORDER.detail,
          label: 'data(label)',
          color: '#dee3e8',
          'font-weight': 600,
          'font-size': DETAIL_LABEL.size,
          'min-zoomed-font-size': DETAIL_LABEL.minPx,
          'text-wrap': 'wrap',
          'text-max-width': `${DETAIL_LABEL.width}px`,
          'text-valign': 'top',
          'text-halign': 'center',
          'text-margin-y': -10,
          'z-index': Z.disc
        }
      },
      { selector: 'node.disc:active', style: { 'overlay-opacity': 0 } },
      {
        selector: 'node.disc.ov',
        style: {
          'border-width': DISC_BORDER.overview,
          'font-size': 'data(font)',
          'min-zoomed-font-size': OVERVIEW_LABEL.minPx,
          label: 'data(overviewLabel)',
          'text-max-width': '100000px',
          'text-valign': 'center',
          'text-margin-y': 0,
          'background-color': 'data(fill)',
          'background-opacity': 1,
          'border-opacity': 0.95,
          'z-index': Z.overviewDisc
        }
      },
      { selector: 'node.disc.group', style: { 'background-opacity': 0.05, 'border-opacity': 0.35 } },
      { selector: 'node.disc.group.ov', style: { 'background-opacity': 1, 'border-opacity': 0.5 } },
      { selector: 'node.disc.focused', style: { 'background-opacity': 0.16, 'border-opacity': 1, 'z-index': Z.litDisc } },
      {
        selector: 'node.disc.ov.focused',
        style: { 'background-opacity': 1, 'border-color': '#fff', 'z-index': Z.overviewDisc }
      },
      {
        selector: 'edge.link',
        style: {
          'curve-style': 'straight',
          'line-color': '#9ab',
          width: `mapData(strength, 0, 1, ${LINK_WIDTH.detail[0]}, ${LINK_WIDTH.detail[1]})`,
          opacity: (ele: any) => 0.14 + 0.42 * ele.data('strength'),
          'z-index': Z.link
        }
      },
      {
        selector: 'edge.link.ov',
        style: { width: `mapData(strength, 0, 1, ${LINK_WIDTH.overview[0]}, ${LINK_WIDTH.overview[1]})` }
      },
      {
        selector: 'edge.film-edge',
        style: { 'curve-style': 'straight', 'line-color': 'data(color)', width: 3, opacity: 0.8, 'z-index': Z.edge }
      },
      {
        selector: 'node.film',
        style: {
          shape: 'round-rectangle',
          width: 64,
          height: 96,
          'background-color': '#2c3440',
          'background-image': (ele: any) => {
            const p = ele.data('posterPath') as string | null;
            return p ? `${posterBase}${p}` : 'none';
          },
          'background-image-crossorigin': 'null',
          'background-fit': 'cover',
          'background-clip': 'node',
          'border-width': 3,
          'border-color': 'data(color)',
          label: 'data(label)',
          'font-size': 14,
          color: '#9ab',
          'text-valign': 'bottom',
          'text-halign': 'center',
          'text-margin-y': 5,
          'text-max-width': '80px',
          'text-wrap': 'ellipsis',
          'min-zoomed-font-size': 7,
          'z-index': Z.film
        }
      },
      { selector: 'node.film.bridge', style: { 'border-style': 'dashed', 'border-width': 4 } },
      { selector: 'node.film.added', style: { 'border-color': '#9ab', 'border-style': 'dotted' } },
      { selector: 'edge.film-edge.ov', style: { width: 12 } },
      { selector: '.filtered, .ov-hidden', style: { display: 'none' } },
      { selector: 'node.film.lit', style: { 'z-index': Z.lit } },
      { selector: 'node.film.lit.ov-hidden, node.film.path-node.ov-hidden', style: { display: 'element' } },
      { selector: 'node.film.selected', style: { 'border-width': 5, 'border-color': '#40bcf4', 'border-style': 'solid' } },
      {
        selector: 'node.film.path-node',
        style: { 'border-width': 5, 'border-color': '#ff8000', 'border-style': 'solid', 'z-index': Z.path }
      },
      {
        selector: 'edge.path-edge',
        style: { 'curve-style': 'straight', 'line-color': '#ff8000', width: 5, opacity: 1, 'z-index': Z.pathEdge }
      },
      { selector: 'edge.path-edge.ov', style: { width: 28 } }
    ];
  }
}

function overviewLines(name: string, films: number): string[] {
  const lines: string[] = [];
  for (const word of name.split(/\s+|(?<=-)/).filter(Boolean)) {
    const last = lines[lines.length - 1];
    const joined = last === undefined ? word : last.endsWith('-') ? last + word : `${last} ${word}`;
    if (last !== undefined && joined.length <= OVERVIEW_LABEL.lineChars) lines[lines.length - 1] = joined;
    else lines.push(word);
  }
  return [...lines, `${films} films`];
}

function overviewFont(lines: string[], diameter: number): number {
  const longest = Math.max(...lines.map(l => l.length));
  const fitted = diameter * OVERVIEW_LABEL.widthPerDiameter / (OVERVIEW_LABEL.charWidth * longest);
  return Math.min(diameter * OVERVIEW_LABEL.perDiameter, fitted);
}

function blend(hex: string, base: string, amount: number): string {
  const channel = (h: string, i: number) => parseInt(h.slice(1 + 2 * i, 3 + 2 * i), 16);
  return '#' + [0, 1, 2]
    .map(i => Math.round(channel(base, i) + (channel(hex, i) - channel(base, i)) * amount))
    .map(v => v.toString(16).padStart(2, '0'))
    .join('');
}

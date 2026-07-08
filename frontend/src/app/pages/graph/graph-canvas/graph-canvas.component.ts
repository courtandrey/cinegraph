import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnChanges,
  AfterViewInit,
  OnDestroy,
  SimpleChanges,
  ElementRef,
  ViewChild,
  NgZone,
  ChangeDetectionStrategy,
  signal
} from '@angular/core';
import cytoscape, { Core, ElementDefinition } from 'cytoscape';
import { GraphPayload } from '../../../models/movie.model';

const POSTER_W185 = 'https://image.tmdb.org/t/p/w185';
const POSTER_W92 = 'https://image.tmdb.org/t/p/w92';

const LARGE_NODE_COUNT = 150;
const LARGE_EDGE_COUNT = 400;

const FOCUS_ZOOM = 1.8;

@Component({
  selector: 'app-graph-canvas',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './graph-canvas.component.html',
  styleUrl: './graph-canvas.component.scss'
})
export class GraphCanvasComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild('container') containerRef!: ElementRef<HTMLDivElement>;

  @Input() graph!: GraphPayload;
  @Input() centerId!: number;
  @Input() minScore = 12;
  @Input() selectedNodeId: number | null = null;
  @Input() layoutByInScore = false;
  @Input() focus: { id: number } | null = null;
  @Input() pathNodeIds: number[] | null = null;
  @Input() pathRightInset = 0;
  @Input() nodeSortScore: Map<number, number> | null = null;

  @Output() nodeTap = new EventEmitter<number>();
  @Output() clearSelection = new EventEmitter<void>();

  readonly tooltipVisible = signal(false);
  readonly tooltipX = signal(0);
  readonly tooltipY = signal(0);
  readonly tooltipText = signal('');

  private cy: Core | null = null;
  private large = false;
  private pendingFocus: number | null = null;
  private pendingPath = false;
  private lastLayoutCenter: number | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private resizeRaf = 0;
  private lastW = 0;
  private lastH = 0;

  constructor(private ngZone: NgZone) {}

  ngAfterViewInit(): void {
    this.initCytoscape();
    this.observeResize();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.cy) return;
    const rebuilt = !!(changes['graph'] || changes['minScore']);
    if (rebuilt) {
      this.rebuildGraph();
    } else if (changes['selectedNodeId']) {
      this.applySelection();
    }
    if (changes['focus'] && this.focus) {
      this.pendingFocus = this.focus.id;
      if (!rebuilt) this.runPendingFocus();
    }
    if (changes['pathNodeIds']) {
      this.pendingPath = true;
      if (!rebuilt) this.runPendingPath();
    }
    if (changes['nodeSortScore'] && !rebuilt) this.relayout();
  }

  private relayout(): void {
    if (!this.cy) return;
    this.ngZone.runOutsideAngular(() => {
      const layout = this.cy!.layout(this.buildLayout(false) as any);
      layout.run();
    });
  }

  hideTooltip(): void {
    this.tooltipVisible.set(false);
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    if (this.resizeRaf) cancelAnimationFrame(this.resizeRaf);
    this.cy?.destroy();
    this.cy = null;
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
          const cy = this.cy;
          if (!cy) return;
          cy.resize();
          cy.fit(undefined, this.fitPadding());
        });
      });
      this.resizeObserver.observe(el);
    });
  }

  private fitPadding(): number {
    return this.containerRef.nativeElement.clientWidth < 640 ? 24 : 60;
  }

  private initCytoscape(): void {
    this.ngZone.runOutsideAngular(() => {
      this.lastLayoutCenter = this.centerId;
      this.large =
        (this.graph?.nodes?.length ?? 0) > LARGE_NODE_COUNT ||
        (this.graph?.edges?.length ?? 0) > LARGE_EDGE_COUNT;
      this.cy = cytoscape({
        container: this.containerRef.nativeElement,
        elements: this.buildElements(),
        style: this.buildStyle() as any,
        layout: this.buildLayout(true) as any,
        motionBlur: false,
        pixelRatio: this.large ? 1 : 'auto'
      });

      this.cy.on('tap', 'node', evt => {
        const id = Number(evt.target.id());
        this.ngZone.run(() => this.nodeTap.emit(id));
      });

      this.cy.on('tap', evt => {
        if ((evt.target as any) === this.cy) {
          this.ngZone.run(() => this.clearSelection.emit());
        }
      });

      this.cy.on('mouseover', 'edge', evt => {
        const edge = evt.target;
        edge.addClass('hover');
        const score = edge.data('score') as number;
        const reason = edge.data('topReason') as string;
        const pos = evt.renderedPosition;
        this.ngZone.run(() => {
          this.tooltipText.set(reason ? `${reason} · ${score.toFixed(1)}` : score.toFixed(1));
          this.tooltipX.set(pos.x + 8);
          this.tooltipY.set(pos.y - 28);
          this.tooltipVisible.set(true);
        });
      });

      this.cy.on('mouseout', 'edge', evt => {
        evt.target.removeClass('hover');
        this.ngZone.run(() => this.tooltipVisible.set(false));
      });

      this.cy.on('mousemove', 'edge', evt => {
        const pos = evt.renderedPosition;
        this.ngZone.run(() => {
          this.tooltipX.set(pos.x + 8);
          this.tooltipY.set(pos.y - 28);
        });
      });

      this.cy.on('mouseover', 'node', evt => {
        const node = evt.target;
        const pos  = evt.renderedPosition;
        this.ngZone.run(() => {
          this.tooltipText.set(node.data('label') as string);
          this.tooltipX.set(pos.x + 8);
          this.tooltipY.set(pos.y - 36);
          this.tooltipVisible.set(true);
        });
      });

      this.cy.on('mouseout', 'node', () => {
        this.ngZone.run(() => this.tooltipVisible.set(false));
      });

      this.cy.on('mousemove', 'node', evt => {
        const pos = evt.renderedPosition;
        this.ngZone.run(() => {
          this.tooltipX.set(pos.x + 8);
          this.tooltipY.set(pos.y - 36);
        });
      });

      this.cy.on('tap', 'edge.inter-neighbor', evt => {
        const edge = evt.target;
        const wasLit = edge.hasClass('edge-lit');
        this.cy!.edges().removeClass('edge-lit');
        if (!wasLit) edge.addClass('edge-lit');
      });
    });
  }

  private rebuildGraph(): void {
    if (!this.cy) return;
    this.ngZone.runOutsideAngular(() => {
      const cy = this.cy!;
      const defs = this.buildElements();
      const defIds = new Set(defs.map(d => String(d.data['id'])));

      cy.batch(() => {
        cy.elements().filter(ele => !defIds.has(ele.id())).remove();

        const existing = new Set(cy.elements().map(ele => ele.id()));
        for (const def of defs) {
          const id = String(def.data['id']);
          if (existing.has(id)) {
            const ele = cy.$id(id);
            ele.data(def.data as any);
            ele.classes((def.classes as string) ?? '');
          } else {
            cy.add(def);
          }
        }

        cy.style(this.buildStyle() as any);
      });

      const fit = this.lastLayoutCenter !== this.centerId;
      this.lastLayoutCenter = this.centerId;
      const layout = cy.layout(this.buildLayout(fit) as any);
      layout.one('layoutstop', () => { this.runPendingFocus(); this.runPendingPath(); });
      layout.run();

      this.applySelection();
    });
  }

  private runPendingPath(): void {
    if (!this.pendingPath || !this.cy) return;
    this.pendingPath = false;
    this.applyPath();
  }

  private applyPath(): void {
    if (!this.cy) return;
    const ids = this.pathNodeIds;
    this.ngZone.runOutsideAngular(() => {
      const cy = this.cy!;
      let nodes = cy.collection();
      cy.batch(() => {
        cy.elements().removeClass('path-node path-edge');
        if (!ids || ids.length === 0) return;
        for (const id of ids) nodes = nodes.union(cy.$id(String(id)).addClass('path-node'));
        for (let i = 0; i + 1 < ids.length; i++) {
          cy.$id(`e_${ids[i]}_${ids[i + 1]}`)
            .union(cy.$id(`e_${ids[i + 1]}_${ids[i]}`))
            .addClass('path-edge');
        }
      });
      if (nodes.nonempty()) {
        cy.stop();
        cy.animate(this.fitViewport(nodes), { duration: 450, easing: 'ease-in-out-cubic' });
      }
    });
  }

  private fitViewport(eles: cytoscape.Collection): { zoom: number; pan: { x: number; y: number } } {
    const cy = this.cy!;
    const el = this.containerRef.nativeElement;
    const pad = this.fitPadding();
    const inset = Math.max(0, this.pathRightInset);
    const availW = Math.max(60, el.clientWidth - inset - 2 * pad);
    const availH = Math.max(60, el.clientHeight - 2 * pad);

    const bb = eles.boundingBox({});
    const zoom = Math.max(cy.minZoom(),
      Math.min(cy.maxZoom(), 1.5, availW / bb.w, availH / bb.h));

    return {
      zoom,
      pan: {
        x: (el.clientWidth - inset) / 2 - zoom * (bb.x1 + bb.w / 2),
        y: el.clientHeight / 2 - zoom * (bb.y1 + bb.h / 2)
      }
    };
  }

  private runPendingFocus(): void {
    const id = this.pendingFocus;
    if (id == null || !this.cy) return;
    this.pendingFocus = null;
    const node = this.cy.$id(String(id));
    if (!node.length) return;
    this.ngZone.runOutsideAngular(() => {
      this.cy!.stop();
      this.cy!.animate(
        { center: { eles: node }, zoom: FOCUS_ZOOM },
        { duration: 450, easing: 'ease-in-out-cubic' }
      );
    });
  }

  private applySelection(): void {
    if (!this.cy) return;
    this.ngZone.runOutsideAngular(() => {
      const cy = this.cy!;
      cy.batch(() => {
        cy.elements().removeClass('selected dimmed active edge-lit');

        const nodeId = this.selectedNodeId;
        if (nodeId == null) return;

        const sel = cy.$(`#${nodeId}`);
        if (!sel.length) return;
        sel.addClass('selected');

        const adjEdges = sel.connectedEdges();
        const adjNodes = adjEdges.connectedNodes();

        cy.nodes()
          .difference(sel)
          .difference(adjNodes)
          .addClass('dimmed');

        cy.edges('.center-edge')
          .difference(adjEdges)
          .addClass('dimmed');

        adjEdges.filter('.inter-neighbor').addClass('active');
      });
    });
  }

  private buildElements(): ElementDefinition[] {
    if (!this.graph) return [];
    const elements: ElementDefinition[] = [];
    const c = this.graph.center;
    const centerId = this.centerId;

    const nodeIds = new Set<number>([c.id]);

    const centerScore = new Map<number, number>();
    for (const e of this.graph.edges) {
      if (e.source === centerId) centerScore.set(e.target, e.score);
      else if (e.target === centerId) centerScore.set(e.source, e.score);
    }

    elements.push({
      data: { id: String(c.id), label: c.title, posterPath: c.posterPath, score: 99999, isCenter: true }
    });

    for (const n of this.graph.nodes) {
      if (n.id === c.id) continue;
      nodeIds.add(n.id);
      elements.push({
        data: { id: String(n.id), label: n.title, posterPath: n.posterPath, score: centerScore.get(n.id) ?? 0, isCenter: false }
      });
    }

    for (const e of this.graph.edges) {
      if (!nodeIds.has(e.source) || !nodeIds.has(e.target)) continue;
      const isCenterEdge = e.source === centerId || e.target === centerId;
      elements.push({
        data: {
          id: `e_${e.source}_${e.target}`,
          source: String(e.source),
          target: String(e.target),
          score: e.score,
          topReason: e.topReason
        },
        classes: isCenterEdge ? 'center-edge' : 'inter-neighbor'
      });
    }

    return elements;
  }

  private buildStyle(): object[] {
    const centerId = this.centerId;
    const centerScores = (this.graph?.edges ?? [])
      .filter(e => e.source === centerId || e.target === centerId)
      .map(e => e.score);
    const maxScore = centerScores.length ? Math.max(...centerScores) : 80;
    const minScore = this.minScore;
    const posterBase = this.large ? POSTER_W92 : POSTER_W185;

    return [
      {
        selector: 'node',
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
          'border-width': 0,
          label: 'data(label)',
          'font-size': 14,
          color: '#9ab',
          'text-valign': 'bottom',
          'text-halign': 'center',
          'text-margin-y': 5,
          'text-max-width': '80px',
          'text-overflow-wrap': 'ellipsis',
          'text-wrap': 'ellipsis',
          'min-zoomed-font-size': 6,
          cursor: 'pointer',
          'transition-property': 'opacity, border-width, border-color',
          'transition-duration': 200
        }
      },
      {
        selector: 'node[?isCenter]',
        style: {
          width: 96,
          height: 144,
          'border-width': 3,
          'border-color': '#00c030'
        }
      },
      {
        selector: 'node.selected',
        style: {
          'border-width': 3,
          'border-color': '#40bcf4'
        }
      },
      {
        selector: 'node.path-node',
        style: {
          width: 120,
          height: 180,
          'border-width': 4,
          'border-color': '#ff8000',
          opacity: 1,
          'z-index': 20
        }
      },
      {
        selector: 'node.path-node[?isCenter]',
        style: {
          width: 136,
          height: 204
        }
      },
      {
        selector: 'node.dimmed',
        style: { opacity: 0.2 }
      },
      {
        selector: 'edge',
        style: {
          'curve-style': 'bezier',
          'transition-property': 'opacity, line-color, width',
          'transition-duration': 150
        }
      },
      {
        selector: 'edge.center-edge',
        style: {
          'line-color': 'rgba(154,170,187,0.5)',
          width: (ele: any) => {
            const s = ele.data('score') as number;
            const ratio = maxScore > minScore ? (s - minScore) / (maxScore - minScore) : 0;
            return 1 + Math.max(0, Math.min(6, ratio * 6));
          },
          opacity: 0.8
        }
      },
      {
        selector: 'edge.center-edge.dimmed',
        style: { opacity: 0.08 }
      },
      {
        selector: 'edge.inter-neighbor',
        style: {
          'curve-style': this.large ? 'straight' : 'bezier',
          'line-color': 'rgba(154,170,187,0.5)',
          width: 1,
          opacity: 0.08
        }
      },
      {
        selector: 'edge.inter-neighbor.active',
        style: { opacity: 0.5 }
      },
      {
        selector: 'edge.hover, edge.edge-lit',
        style: {
          'line-color': '#ff8000',
          opacity: 1
        }
      },
      {
        selector: 'edge.path-edge',
        style: {
          'line-color': '#ff8000',
          width: 4,
          opacity: 1,
          'z-index': 19
        }
      }
    ];
  }

  private buildLayout(fit: boolean): object {
    const MAX_PER_RING = 12;

    const NODE_H   = 96;
    const CENTER_H = 150;
    const LABEL_H  = 20;

    const ARC_PER_NODE = NODE_H + 12;
    const RING_GAP = LABEL_H + 16;

    const GOLDEN = 2.3999632;

    const cid = this.centerId;
    const centerId = String(cid);
    const edges = this.graph?.edges ?? [];

    const scoreByNode = new Map<number, number>();
    if (this.nodeSortScore) {
      for (const n of this.graph?.nodes ?? []) scoreByNode.set(n.id, this.nodeSortScore.get(n.id) ?? 0);
    } else if (this.layoutByInScore) {
      for (const n of this.graph?.nodes ?? []) scoreByNode.set(n.id, n.inScore ?? 0);
    } else {
      for (const e of edges) {
        if (e.source === cid) scoreByNode.set(e.target, e.score);
        else if (e.target === cid) scoreByNode.set(e.source, e.score);
      }
    }

    const neighbors = (this.graph?.nodes ?? [])
      .filter(n => n.id !== cid)
      .map(n => ({ id: String(n.id), score: scoreByNode.get(n.id) ?? 0 }))
      .sort((a, b) => b.score - a.score);

    const bandMap = new Map<number, string[]>();
    for (const { id, score } of neighbors) {
      const key = Math.floor(score / 5) * 5;
      if (!bandMap.has(key)) bandMap.set(key, []);
      bandMap.get(key)!.push(id);
    }
    const sortedBands = [...bandMap.entries()].sort((a, b) => b[0] - a[0]);

    const rings: string[][] = [];
    for (const [, ids] of sortedBands) {
      for (let start = 0; start < ids.length; start += MAX_PER_RING) {
        rings.push(ids.slice(start, start + MAX_PER_RING));
      }
    }

    const positions: Record<string, { x: number; y: number }> = {};
    positions[centerId] = { x: 0, y: 0 };

    let prevR = (CENTER_H - NODE_H) / 2 + LABEL_H;
    for (let ri = 0; ri < rings.length; ri++) {
      const ids    = rings[ri];
      const n      = ids.length;
      const radius = Math.max(
        n * ARC_PER_NODE / (2 * Math.PI),
        prevR + NODE_H + RING_GAP
      );
      const startAngle = (ri * GOLDEN) % (2 * Math.PI);
      prevR = radius;

      for (let i = 0; i < n; i++) {
        const angle = startAngle + (i / n) * 2 * Math.PI;
        positions[ids[i]] = {
          x: Math.cos(angle) * radius,
          y: Math.sin(angle) * radius
        };
      }
    }

    return {
      name: 'preset',
      positions: (node: any) => positions[node.id()] ?? { x: 0, y: 0 },
      animate: true,
      animationDuration: 600,
      fit,
      padding: this.fitPadding()
    };
  }
}

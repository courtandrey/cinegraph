import { Component, EventEmitter, Input, OnChanges, Output, signal, computed } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { MAX_WEIGHT, isComponentCode, roleLabel } from '../../../services/graph-scoring';

export interface RoleSlider {
  code: string;
  weight: number;
  defaultWeight: number;
  count: number;
}

@Component({
  selector: 'app-weights-panel',
  standalone: true,
  imports: [NgTemplateOutlet],
  templateUrl: './weights-panel.component.html',
  styleUrl: './weights-panel.component.scss'
})
export class WeightsPanelComponent implements OnChanges {
  @Input() roles: RoleSlider[] = [];
  @Input() active = false;

  @Output() apply = new EventEmitter<ReadonlyMap<string, number>>();
  @Output() reset = new EventEmitter<void>();
  @Output() closed = new EventEmitter<void>();

  readonly maxWeight = MAX_WEIGHT;
  readonly draft = signal<ReadonlyMap<string, number>>(new Map());

  readonly componentRows = computed(() => this.rows().filter(r => isComponentCode(r.code)));
  readonly roleRows = computed(() => this.rows().filter(r => !isComponentCode(r.code)));

  readonly dirty = computed(() =>
    this.roles.some(r => this.draftValue(r) !== r.weight));

  private readonly rows = signal<RoleSlider[]>([]);

  ngOnChanges(): void {
    this.rows.set(this.roles);
    this.draft.set(new Map(this.roles.map(r => [r.code, r.weight])));
  }

  label(code: string): string {
    return roleLabel(code);
  }

  draftValue(role: RoleSlider): number {
    return this.draft().get(role.code) ?? role.weight;
  }

  onSlide(code: string, value: string): void {
    const next = new Map(this.draft());
    next.set(code, Number(value));
    this.draft.set(next);
  }

  onApply(): void {
    this.apply.emit(this.draft());
  }

  onReset(): void {
    this.draft.set(new Map(this.roles.map(r => [r.code, r.defaultWeight])));
    this.reset.emit();
  }
}

import { Component, inject, signal } from '@angular/core';
import { AnalyticsService } from '../services/analytics.service';

@Component({
  selector: 'app-consent-banner',
  standalone: true,
  templateUrl: './consent-banner.component.html',
  styleUrl: './consent-banner.component.scss'
})
export class ConsentBannerComponent {
  private analytics = inject(AnalyticsService);

  readonly visible = signal(this.analytics.enabled && this.analytics.consentState() === 'unset');

  accept(): void {
    this.analytics.grantConsent();
    this.visible.set(false);
  }

  decline(): void {
    this.analytics.denyConsent();
    this.visible.set(false);
  }
}

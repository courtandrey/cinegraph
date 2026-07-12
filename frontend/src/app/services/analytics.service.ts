import { Injectable, inject } from '@angular/core';
import { Router, NavigationEnd } from '@angular/router';
import { filter } from 'rxjs/operators';
import { environment } from '../../environments/environment';

declare global {
  interface Window {
    dataLayer: unknown[];
    gtag: (...args: unknown[]) => void;
    __APP_CONFIG__?: { gaMeasurementId?: string; siteUrl?: string };
  }
}

const CONSENT_KEY = 'ga-consent';


@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private router = inject(Router);
  private tracking = false;
  private navSubscribed = false;

  private get measurementId(): string {
    const runtime = window.__APP_CONFIG__?.gaMeasurementId;
    return typeof runtime === 'string' && runtime ? runtime : environment.gaMeasurementId;
  }

  get enabled(): boolean {
    return environment.production && !!this.measurementId && typeof window.gtag === 'function';
  }

  consentState(): 'granted' | 'denied' | 'unset' {
    const v = localStorage.getItem(CONSENT_KEY);
    return v === 'granted' || v === 'denied' ? v : 'unset';
  }

  init(): void {
    if (this.consentState() === 'granted') this.enable();
  }

  grantConsent(): void {
    localStorage.setItem(CONSENT_KEY, 'granted');
    this.enable();
  }

  denyConsent(): void {
    localStorage.setItem(CONSENT_KEY, 'denied');
    if (typeof window.gtag === 'function') {
      window.gtag('consent', 'update', { analytics_storage: 'denied' });
    }
  }

  private enable(): void {
    if (!this.enabled || this.tracking) return;
    this.tracking = true;

    window.gtag('consent', 'update', { analytics_storage: 'granted' });
    this.pageView(this.router.url);
    this.trackNavigations();
  }

  private trackNavigations(): void {
    if (this.navSubscribed) return;
    this.navSubscribed = true;
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(e => this.pageView(e.urlAfterRedirects));
  }

  private pageView(path: string): void {
    if (!this.tracking) return;
    window.gtag('event', 'page_view', {
      page_path: path,
      page_location: window.location.href,
      page_title: document.title
    });
  }
}

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
  private loaded = false;
  private navSubscribed = false;

  private get measurementId(): string {
    const runtime = window.__APP_CONFIG__?.gaMeasurementId;
    return typeof runtime === 'string' && runtime ? runtime : environment.gaMeasurementId;
  }

  get enabled(): boolean {
    return environment.production && !!this.measurementId;
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
  }

  private enable(): void {
    if (!this.enabled || this.loaded) return;
    this.loaded = true;

    const id = this.measurementId;
    const script = document.createElement('script');
    script.async = true;
    script.src = `https://www.googletagmanager.com/gtag/js?id=${id}`;
    document.head.appendChild(script);

    window.dataLayer = window.dataLayer || [];
    window.gtag = function gtag() { window.dataLayer.push(arguments); };
    window.gtag('js', new Date());
    window.gtag('config', id, { send_page_view: false });

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
    if (!this.loaded) return;
    window.gtag('event', 'page_view', {
      page_path: path,
      page_location: window.location.href,
      page_title: document.title
    });
  }
}

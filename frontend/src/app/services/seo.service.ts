import { Injectable, inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { Meta, Title } from '@angular/platform-browser';

export interface PageSeo {
  title: string;
  description: string;
  canonicalPath?: string;
  image?: string | null;
  noindex?: boolean;
  jsonLd?: object | null;
}

const JSON_LD_ID = 'seo-jsonld';

function resolveSiteUrl(): string {
  const runtime = window.__APP_CONFIG__?.siteUrl;
  const url = typeof runtime === 'string' && runtime.trim() ? runtime.trim() : window.location.origin;
  return url.replace(/\/+$/, '');
}

@Injectable({ providedIn: 'root' })
export class SeoService {
  private title = inject(Title);
  private meta = inject(Meta);
  private doc = inject(DOCUMENT);

  readonly siteUrl = resolveSiteUrl();
  private readonly defaultImage = `${this.siteUrl}/og-cover.png`;

  apply(seo: PageSeo): void {
    this.title.setTitle(seo.title);
    const url = seo.canonicalPath ? this.siteUrl + seo.canonicalPath : null;
    const image = seo.image ?? this.defaultImage;

    this.meta.updateTag({ name: 'description', content: seo.description });
    this.meta.updateTag({ property: 'og:title', content: seo.title });
    this.meta.updateTag({ property: 'og:description', content: seo.description });
    this.meta.updateTag({ property: 'og:image', content: image });
    this.meta.updateTag({ name: 'twitter:title', content: seo.title });
    this.meta.updateTag({ name: 'twitter:description', content: seo.description });
    this.meta.updateTag({ name: 'twitter:image', content: image });

    if (seo.noindex) {
      this.meta.updateTag({ name: 'robots', content: 'noindex' });
    } else {
      this.meta.removeTag('name="robots"');
    }

    this.setCanonical(url);
    this.setOgUrl(url);
    this.setJsonLd(seo.jsonLd ?? null);
  }

  private setCanonical(url: string | null): void {
    let link = this.doc.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    if (!url) {
      link?.remove();
      return;
    }
    if (!link) {
      link = this.doc.createElement('link');
      link.setAttribute('rel', 'canonical');
      this.doc.head.appendChild(link);
    }
    link.setAttribute('href', url);
  }

  private setOgUrl(url: string | null): void {
    if (url) {
      this.meta.updateTag({ property: 'og:url', content: url });
    } else {
      this.meta.removeTag('property="og:url"');
    }
  }

  private setJsonLd(data: object | null): void {
    this.doc.getElementById(JSON_LD_ID)?.remove();
    if (!data) return;
    const script = this.doc.createElement('script');
    script.id = JSON_LD_ID;
    script.type = 'application/ld+json';
    script.text = JSON.stringify(data);
    this.doc.head.appendChild(script);
  }
}

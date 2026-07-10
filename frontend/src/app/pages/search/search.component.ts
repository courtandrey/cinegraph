import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SearchBoxComponent } from './search-box/search-box.component';
import { LetterboxdUploadComponent } from './letterboxd-upload/letterboxd-upload.component';
import { SeoService } from '../../services/seo.service';

@Component({
  selector: 'app-search',
  standalone: true,
  imports: [SearchBoxComponent, LetterboxdUploadComponent, RouterLink],
  templateUrl: './search.component.html',
  styleUrl: './search.component.scss'
})
export class SearchComponent implements OnInit {
  private seo = inject(SeoService);

  readonly exampleFilms = [
    { id: 680, title: 'Pulp Fiction' },
    { id: 238, title: 'The Godfather' },
    { id: 27205, title: 'Inception' },
    { id: 496243, title: 'Parasite' },
    { id: 129, title: 'Spirited Away' }
  ];

  ngOnInit(): void {
    this.seo.apply({
      title: 'CineGraph — Interactive Movie Similarity Graph & Letterboxd Recommendations',
      description: 'Explore an interactive graph of 1.1M+ films connected by shared directors, writers, cinematographers and cast. Find movies like your favorites, get Letterboxd recommendations, and trace the shortest path between any two films.',
      canonicalPath: '/',
      jsonLd: {
        '@context': 'https://schema.org',
        '@type': 'WebSite',
        name: 'CineGraph',
        alternateName: 'CineGraph Movie Similarity Graph',
        url: `${this.seo.siteUrl}/`,
        description: 'Interactive movie similarity graph: films connected by shared crew and cast, Letterboxd-based recommendations, and shortest paths between films.'
      }
    });
  }
}

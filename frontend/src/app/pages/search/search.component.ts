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
      title: 'CineGraph — Movie Similarity Graph & Recommendations',
      description: 'Explore a graph of 1M+ films linked by shared cast and crew. Find similar movies, get Letterboxd recommendations, and trace paths between films.',
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

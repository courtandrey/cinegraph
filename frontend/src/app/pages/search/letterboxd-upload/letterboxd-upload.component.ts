import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MovieApiService } from '../../../services/movie-api.service';
import { LetterboxdStore } from '../../../services/letterboxd-store.service';

@Component({
  selector: 'app-letterboxd-upload',
  standalone: true,
  imports: [],
  templateUrl: './letterboxd-upload.component.html',
  styleUrl: './letterboxd-upload.component.scss'
})
export class LetterboxdUploadComponent {
  private api = inject(MovieApiService);
  private store = inject(LetterboxdStore);
  private router = inject(Router);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;

    this.loading.set(true);
    this.error.set(null);
    this.api.uploadLetterboxd(file).subscribe({
      next: res => {
        this.loading.set(false);
        if (!res.graphs.length) {
          this.error.set('No connected films found in that export.');
          return;
        }
        this.store.set(res.hash, res.graphs);
        this.router.navigate(['/letterboxd', res.hash]);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not build a graph from that file. Please try again.');
      }
    });
  }
}

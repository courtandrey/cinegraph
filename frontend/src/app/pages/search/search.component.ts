import { Component } from '@angular/core';
import { SearchBoxComponent } from './search-box/search-box.component';
import { LetterboxdUploadComponent } from './letterboxd-upload/letterboxd-upload.component';

@Component({
  selector: 'app-search',
  standalone: true,
  imports: [SearchBoxComponent, LetterboxdUploadComponent],
  templateUrl: './search.component.html',
  styleUrl: './search.component.scss'
})
export class SearchComponent {}

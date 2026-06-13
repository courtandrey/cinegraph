import { Component } from '@angular/core';
import { SearchBoxComponent } from './search-box/search-box.component';

@Component({
  selector: 'app-search',
  standalone: true,
  imports: [SearchBoxComponent],
  templateUrl: './search.component.html',
  styleUrl: './search.component.scss'
})
export class SearchComponent {}

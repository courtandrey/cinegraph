import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ConsentBannerComponent } from './consent-banner/consent-banner.component';
import { AnalyticsService } from './services/analytics.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, ConsentBannerComponent],
  template: '<router-outlet /><app-consent-banner />',
  styles: [':host { display: block; height: 100vh; }']
})
export class AppComponent {
  constructor() {
    inject(AnalyticsService).init();
  }
}

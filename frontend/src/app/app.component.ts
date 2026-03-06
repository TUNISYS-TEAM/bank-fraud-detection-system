// app.component.ts
import { Component, OnInit, inject } from '@angular/core';
import { AuthService } from './core/services/auth.service';
import { AuthStore } from './core/store/auth.store';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit {
  private authService = inject(AuthService);
  private authStore = inject(AuthStore);

  ngOnInit() {
    // First fetch CSRF token
    this.authService.fetchCsrfToken().subscribe({
      next: () => {
        if (this.hasSessionCookie()) {
          this.authStore.restoreSession();
        }
        this.authService.setupAutoRefresh();
      },
      error: (err) => {
        console.error('Failed to initialize CSRF, but continuing...', err);
        // Even if CSRF fails, check for session
        if (this.hasSessionCookie()) {
          this.authStore.restoreSession();
        }
        this.authService.setupAutoRefresh();
      }
    });
  }

  /**
   * Check if session cookie exists
   */
  private hasSessionCookie(): boolean {
    const cookies = document.cookie.split(';');
    return cookies.some(cookie => cookie.trim().startsWith('XSRF-TOKEN='));
  }
}
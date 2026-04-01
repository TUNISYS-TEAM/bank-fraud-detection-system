import { Component, OnInit, inject } from '@angular/core';
import { AuthStore } from './core/store/auth.store';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit {
  private authStore = inject(AuthStore);

  ngOnInit() {
    // Restore session on app startup
    this.authStore.restoreSession().subscribe({
      next: (isValid) => {
        if (isValid) {
          console.log('Session restored successfully');
        } else {
          console.log('No active session found');
        }
      },
      error: (err) => {
        console.error('Session restoration error:', err);
      }
    });
  }
}
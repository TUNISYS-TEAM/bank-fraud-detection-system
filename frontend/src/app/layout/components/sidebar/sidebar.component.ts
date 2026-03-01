import { Component, inject } from '@angular/core';
import { AuthStore } from '../../../core/store/auth.store';

@Component({
  selector: 'app-sidebar',
  templateUrl: './sidebar.component.html',
  styleUrls: ['./sidebar.component.css']
})
export class SidebarComponent {
  readonly authStore = inject(AuthStore);
  
  menuItems = this.authStore.mainPages;

  logout(): void {
    if (confirm('Êtes-vous sûr de vouloir vous déconnecter ?')) {
      this.authStore.logout();
    }
  }
}
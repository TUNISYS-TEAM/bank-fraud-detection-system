import { Component, inject, signal } from '@angular/core';
import { AuthStore } from '../../../core/store/auth.store';

@Component({
  selector: 'app-sidebar',
  templateUrl: './sidebar.component.html',
  styleUrls: ['./sidebar.component.css']
})
export class SidebarComponent {
  readonly authStore = inject(AuthStore);
  
  // Signal pour gérer l'état réduit/étendu
  isCollapsed = signal(false);
  
  menuItems = this.authStore.mainPages;

  toggleSidebar(): void {
    this.isCollapsed.update(value => !value);
  }

  logout(): void {
    if (confirm('Êtes-vous sûr de vouloir vous déconnecter ?')) {
      this.authStore.logout();
    }
  }
}
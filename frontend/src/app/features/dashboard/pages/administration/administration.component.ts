import { Component, inject } from '@angular/core';
import { AuthStore } from '../../../../core/store/auth.store';

@Component({
  selector: 'app-administration',
  templateUrl: './administration.component.html',
  styleUrls: ['./administration.component.css']
})
export class AdministrationComponent {
  authStore = inject(AuthStore);
  
  tabs = this.authStore.administrationTabs;
}
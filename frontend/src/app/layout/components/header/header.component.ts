import { Component, inject } from '@angular/core';
import { AuthStore } from '../../../core/store/auth.store';

@Component({
  selector: 'app-header',
  templateUrl: './header.component.html',
  styleUrl: './header.component.css'
})
export class HeaderComponent {
  readonly authStore = inject(AuthStore);
}
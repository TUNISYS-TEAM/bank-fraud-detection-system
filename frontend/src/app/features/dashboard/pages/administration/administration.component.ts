import { Component, inject, signal, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../environments/environment';
import { AuthStore } from '../../../../core/store/auth.store';
import { KpiCard } from '../../../../shared/cards/cards.component';

@Component({
  selector: 'app-administration',
  templateUrl: './administration.component.html',
  styleUrls: ['./administration.component.css']
})
export class AdministrationComponent implements OnInit {
  private http = inject(HttpClient);
  authStore = inject(AuthStore);

  tabs = this.authStore.administrationTabs;

  kpiCards = signal<KpiCard[]>([
    {
      label: 'Utilisateurs Actifs',
      value: 'Chargement...',
      icon: 'users',
      color: '#4f46e5'
    },
    {
      label: 'Datasets Stockés',
      value: 'non renseignée',
      icon: 'database',
      color: '#a855f7'
    },
    {
      label: 'Espace Utilisé',
      value: 'non renseignée',
      icon: 'file-text',
      color: '#22c55e'
    },
    {
      label: 'Logs Aujourd\'hui',
      value: 'non renseignée',
      icon: 'settings',
      color: '#f97316'
    }
  ]);

  ngOnInit(): void {
    this.loadUserCount();
  }

  private loadUserCount(): void {
    this.http.get<number>(`${environment.apiUrl}/users/count/active`).subscribe({
      next: (count) => {
        this.kpiCards.update(cards => {
          const newCards = [...cards];
          newCards[0].value = count ?? 'non renseignée';
          return newCards;
        });
      },
      error: (err) => {
        console.error('Error fetching user count:', err);
        this.kpiCards.update(cards => {
          const newCards = [...cards];
          newCards[0].value = 'non renseignée';
          return newCards;
        });
      }
    });
  }
}
import { Component, inject, signal, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
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
      value: '...',
      icon: 'users',
      color: '#4f46e5'
    },
    {
      label: 'Datasets Stockés',
      value: '...',
      icon: 'database',
      color: '#a855f7'
    },
    {
      label: 'Espace Utilisé',
      value: '...',
      icon: 'file-text',
      color: '#22c55e'
    },
    {
      label: 'Logs Aujourd\'hui',
      value: '...',
      icon: 'settings',
      color: '#f97316'
    }
  ]);

  ngOnInit(): void {
    this.loadStats();
  }

  private loadStats(): void {
    const activeUsers$ = this.http.get<number>(`${environment.apiUrl}/users/count/active`).pipe(catchError(() => of(0)));
    const datasetCount$ = this.http.get<number>(`${environment.apiUrl}/datasets/count`).pipe(catchError(() => of(0)));
    const totalSize$ = this.http.get<number>(`${environment.apiUrl}/datasets/total-size`).pipe(catchError(() => of(0)));
    const logsToday$ = this.http.get<number>(`${environment.apiUrl}/logs/count/today`).pipe(catchError(() => of(0)));

    forkJoin({
      activeUsers: activeUsers$,
      datasetCount: datasetCount$,
      totalSize: totalSize$,
      logsToday: logsToday$
    }).subscribe({
      next: (stats) => {
        this.kpiCards.update(cards => {
          const newCards = [...cards];
          newCards[0].value = stats.activeUsers ?? 0;
          newCards[1].value = stats.datasetCount ?? 0;
          newCards[2].value = (stats.totalSize ? stats.totalSize.toFixed(2) : '0') + ' Mo';
          newCards[3].value = stats.logsToday ?? 0;
          return newCards;
        });
      },
      error: (err) => {
        console.error('Error fetching admin stats:', err);
        this.kpiCards.update(cards => {
          return cards.map(c => ({ ...c, value: 'Indisponible' }));
        });
      }
    });
  }
}
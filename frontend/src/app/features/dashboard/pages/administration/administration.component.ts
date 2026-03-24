import { Component, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { environment } from '../../../../../environments/environment';
import { AuthStore } from '../../../../core/store/auth.store';
import { KpiCard } from '../../../../shared/cards/cards.component';
import { DatasetService } from '../../../../core/services/dataset.service';
import { StatsService } from '../../../../core/services/stats.service';

@Component({
  selector: 'app-administration',
  templateUrl: './administration.component.html',
  styleUrls: ['./administration.component.css']
})
export class AdministrationComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  authStore = inject(AuthStore);
  private statsService = inject(StatsService);
  private statsSubscription?: Subscription;

  tabs = this.authStore.administrationTabs;

  kpiCards = signal<KpiCard[]>([
    {
      label: 'Utilisateurs Actifs',
      value: '...',
      icon: 'user-check',
      color: '#4f46e5'
    },
    {
      label: 'Rôles Système',
      value: '...',
      icon: 'shield',
      color: '#06b6d4'
    },
    {
      label: 'Sessions Aujourd\'hui',
      value: '...',
      icon: 'activity',
      color: '#8b5cf6'
    },
    {
      label: 'Logs Aujourd\'hui',
      value: '...',
      icon: 'file-text',
      color: '#f97316'
    }
  ]);

  ngOnInit(): void {
    this.loadStats();

    // S'abonner aux notifications de changement de stats
    this.statsSubscription = this.statsService.refreshStats$.subscribe(() => {
      this.loadStats();
    });
  }

  ngOnDestroy(): void {
    if (this.statsSubscription) {
      this.statsSubscription.unsubscribe();
    }
  }

  private loadStats(): void {
    const activeUsers$ = this.http.get<number>(`${environment.apiUrl}/users/count/active`).pipe(catchError(() => of(0)));
    const sessionStats$ = this.http.get<any>(`${environment.apiUrl}/sessions/statistics`).pipe(catchError(() => of({ sessionsToday: 0 })));
    const roleCount$ = this.http.get<number>(`${environment.apiUrl}/roles/count`).pipe(catchError(() => of(0)));
    const logsToday$ = this.http.get<number>(`${environment.apiUrl}/logs/count/today`).pipe(catchError(() => of(0)));

    forkJoin({
      activeUsers: activeUsers$,
      sessionStats: sessionStats$,
      roleCount: roleCount$,
      logsToday: logsToday$
    }).subscribe({
      next: (stats) => {
        this.kpiCards.update(cards => {
          const newCards = [...cards];
          newCards[0].value = stats.activeUsers ?? 0;
          newCards[1].value = stats.roleCount ?? 0;
          newCards[2].value = stats.sessionStats?.sessionsToday ?? 0;
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
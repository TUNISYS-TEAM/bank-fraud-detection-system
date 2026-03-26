import { Component, inject, OnInit, signal, OnDestroy } from '@angular/core';
import { ClientService } from '../../../../core/services/client.service';
import { AccountService } from '../../../../core/services/account.service';
import { AuthStore } from '../../../../core/store/auth.store';
import { KpiCard } from '../../../../shared/cards/cards.component';
import { StatsService } from '../../../../core/services/stats.service';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';

@Component({
  selector: 'app-clients-accounts',
  templateUrl: './clients-accounts.component.html',
  styleUrl: './clients-accounts.component.css'
})
export class ClientsAccountsComponent implements OnInit, OnDestroy {
  private clientService = inject(ClientService);
  private accountService = inject(AccountService);
  private statsService = inject(StatsService);
  authStore = inject(AuthStore);
  private statsSubscription?: Subscription;

  tabs = this.authStore.clientsTabs;

  kpiCards = signal<KpiCard[]>([
    {
      label: 'Nombre de Clients',
      value: '...',
      icon: 'users',
      color: '#4f46e5'
    },
    {
      label: 'Nombre de Comptes',
      value: '...',
      icon: 'credit-card',
      color: '#0ea5e9'
    },
    {
      label: 'Comptes Actifs',
      value: '...',
      icon: 'check-circle',
      color: '#10b981'
    },
    {
      label: 'Solde Total',
      value: '...',
      icon: 'dollar-sign',
      color: '#f59e0b',
      isCurrency: true,
      abbreviate: true
    }
  ]);

  ngOnInit(): void {
    this.loadStats();

    this.statsSubscription = this.statsService.refreshStats$.subscribe(() => {
      this.loadStats();
    });
  }

  ngOnDestroy(): void {
    if (this.statsSubscription) {
      this.statsSubscription.unsubscribe();
    }
  }

  loadStats(): void {
    forkJoin({
      clientCount: this.clientService.getTotalCount().pipe(catchError(() => of(0))),
      accountCount: this.accountService.getTotalCount().pipe(catchError(() => of(0))),
      activeAccountCount: this.accountService.getActiveCount().pipe(catchError(() => of(0))),
      totalBalance: this.accountService.getTotalBalance().pipe(catchError(() => of(0)))
    }).subscribe(({ clientCount, accountCount, activeAccountCount, totalBalance }) => {
      this.kpiCards.update(cards => {
        const newCards = [...cards];
        newCards[0].value = clientCount;
        newCards[1].value = accountCount;
        newCards[2].value = activeAccountCount;
        newCards[3].value = totalBalance;
        return newCards;
      });
    });
  }
}
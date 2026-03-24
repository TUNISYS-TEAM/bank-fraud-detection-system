import { Injectable } from '@angular/core';
import { Subject, Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class StatsService {
  private refreshStatsSubject = new Subject<void>();
  refreshStats$ = this.refreshStatsSubject.asObservable();

  /**
   * Notifie tous les composants abonnés que les statistiques globales 
   * doivent être rafraîchies (suite à un import, suppression, etc.)
   */
  notifyStatsChanged(): void {
    this.refreshStatsSubject.next();
  }
}
import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ForecastKpis } from '../models/forecast.model';

@Injectable({ providedIn: 'root' })
export class ForecastService {
  private http = inject(HttpClient);
  private apiUrl = environment.forecastApiUrl;

  /**
   * Récupère les KPIs pour un code postal donné, ou les KPIs globaux si le code est null/vide.
   * @param postalCode – code postal ou null pour global
   * @param windowDays – horizon de prévision (défaut 7)
   */
  getZoneKpis(postalCode: string | null, windowDays: number = 7): Observable<ForecastKpis> {
    const payload = {
      postalCode: postalCode || null,
      region: postalCode ? 'Tunisie' : 'Tunisie (Global)',
      period: `next_${windowDays}_days`
    };
    return this.http.post<ForecastKpis>(`${this.apiUrl}/kpi/overview-zone`, payload);
  }

  /**
   * Raccourci pour obtenir les KPIs globaux.
   */
  getGlobalKpis(windowDays: number = 7): Observable<ForecastKpis> {
    return this.getZoneKpis(null, windowDays);
  }
}
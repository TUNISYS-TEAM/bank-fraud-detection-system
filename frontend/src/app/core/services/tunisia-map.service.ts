import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import * as topojson from 'topojson-client';

@Injectable({ providedIn: 'root' })
export class TunisiaMapService {
  constructor(private http: HttpClient) {}

  /**
   * Load governorate boundaries as GeoJSON
   */
  getGovernoratesGeoJson(): Observable<any> {
    return this.http.get('assets/geo/tunisiaMap.json').pipe(
      map((topology: any) => {
        // Extract the governorates layer
        return topojson.feature(topology, topology.objects.governorates);
      })
    );
  }
}
import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FeatherModule } from 'angular-feather';

import { DashboardRoutingModule } from './dashboard-routing.module';
import { LayoutModule } from '../../layout/layout.module';

import { OverviewComponent } from './pages/overview/overview.component';
import { AlertsComponent } from './pages/alerts/alerts.component';
import { MlModelsComponent } from './pages/ml-models/ml-models.component';
import { ImportDataComponent } from './pages/import-data/import-data.component';
import { PerformanceComponent } from './pages/performance/performance.component';
import { SimulationComponent } from './pages/simulation/simulation.component';
import { MessagesComponent } from './pages/messaging/messaging.component';

import { CardsComponent } from '../../shared/cards/cards.component';


@NgModule({
  declarations: [
    OverviewComponent,
    AlertsComponent,
    MlModelsComponent,
    ImportDataComponent,
    PerformanceComponent,
    SimulationComponent,
    MessagesComponent, 
  ],
  imports: [
    CommonModule,
    FormsModule, 
    DashboardRoutingModule,
    LayoutModule,
    FeatherModule,
    CardsComponent
  ]
})
export class DashboardModule { }
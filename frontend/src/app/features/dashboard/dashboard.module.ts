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
import { TrainDataComponent } from './pages/train-data/train-data.component';

// Import modal components
import { DatasetCreateModalComponent } from '../../shared/modals/admin-sections/datasets/dataset-create-modal/dataset-create-modal.component';
import { DatasetViewModalComponent } from '../../shared/modals/admin-sections/datasets/dataset-view-modal/dataset-view-modal.component';
import { DatasetUpdateModalComponent } from '../../shared/modals/admin-sections/datasets/dataset-update-modal/dataset-update-modal.component';
import { DatasetDeleteModalComponent } from '../../shared/modals/admin-sections/datasets/dataset-delete-modal/dataset-delete-modal.component';
import { UpdateHistoryModalComponent } from '../../shared/modals/models-ml/history-update-modal/history-update-modal.component';
import { DeleteHistoryModalComponent } from '../../shared/modals/models-ml/history-delete-modal/history-delete-modal.component';


@NgModule({
  declarations: [
    OverviewComponent,
    AlertsComponent,
    MlModelsComponent,
    ImportDataComponent,
    PerformanceComponent,
    SimulationComponent,
    MessagesComponent,
    TrainDataComponent, 
  ],
  imports: [
    CommonModule,
    FormsModule, 
    DashboardRoutingModule,
    LayoutModule,
    FeatherModule,
    CardsComponent,
    DatasetCreateModalComponent,
    DatasetViewModalComponent,
    DatasetDeleteModalComponent,
    DatasetUpdateModalComponent,
    UpdateHistoryModalComponent,
    DeleteHistoryModalComponent
  ]
})
export class DashboardModule { }
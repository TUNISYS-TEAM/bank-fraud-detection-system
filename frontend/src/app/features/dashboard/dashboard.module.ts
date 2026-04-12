import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FeatherModule } from 'angular-feather';

import { DashboardRoutingModule } from './dashboard-routing.module';
import { CardsComponent } from '../../shared/cards/cards.component';
import { LayoutModule } from '../../layout/layout.module';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';

import { OverviewComponent } from './pages/overview/overview.component';
import { AlertsComponent } from './pages/alerts/alerts.component';
import { MlModelsComponent } from './pages/ml-models/ml-models.component';
import { ImportDataComponent } from './pages/import-data/import-data.component';
import { PerformanceComponent } from './pages/performance/performance.component';
import { SimulationComponent } from './pages/simulation/simulation.component';
import { MessagesComponent } from './pages/messaging/messaging.component';
import { TrainDataComponent } from './pages/train-data/train-data.component';
import { DatasetsComponent } from './pages/datasets/datasets.component';
import { UserProfileComponent } from './pages/user-profile/user-profile.component';


// Import modal components
import { DatasetCreateModalComponent } from '../../shared/modals/datasets/dataset-create-modal/dataset-create-modal.component';
import { DatasetDeleteModalComponent } from '../../shared/modals/datasets/dataset-delete-modal/dataset-delete-modal.component';
import { DatasetUpdateModalComponent } from '../../shared/modals/datasets/dataset-update-modal/dataset-update-modal.component';
import { DatasetViewModalComponent } from '../../shared/modals/datasets/dataset-view-modal/dataset-view-modal.component';
import { ClientsViewModalComponent } from '../../shared/modals/clients-accounts/clients-view-modal/clients-view-modal.component';
import { UpdateHistoryModalComponent } from '../../shared/modals/models-ml/history-update-modal/history-update-modal.component';
import { DeleteHistoryModalComponent } from '../../shared/modals/models-ml/history-delete-modal/history-delete-modal.component';
import { ModelDetailsModalComponent } from '../../shared/modals/models-ml/model-details-modal/model-details-modal.component';
import { AlertDetailsModalComponent } from '../../shared/modals/fraud-alerts/alert-details-modal/alert-details-modal.component';
import { AnalystAlertDetailsModalComponent } from '../../shared/modals/fraud-alerts/analyst-alert-details-modal/analyst-alert-details-modal.component';
import { AnalyzedTransactionsComponent } from './pages/analyzed-transactions/analyzed-transactions.component';
import { HistoryComponent } from './pages/history/history.component';


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
    DatasetsComponent,
    AnalyzedTransactionsComponent,
    UserProfileComponent,
    HistoryComponent
  ],
  imports: [
    CommonModule,
    DashboardRoutingModule,
    LayoutModule,
    FeatherModule,
    CardsComponent,
    FormsModule,
    ReactiveFormsModule,
    RouterModule,
    DatasetCreateModalComponent,
    DatasetDeleteModalComponent,
    DatasetUpdateModalComponent,
    DatasetViewModalComponent,
    ClientsViewModalComponent,
    UpdateHistoryModalComponent,
    DeleteHistoryModalComponent,
    ModelDetailsModalComponent,
    AlertDetailsModalComponent,
    AnalystAlertDetailsModalComponent
  ]
})
export class DashboardModule { }
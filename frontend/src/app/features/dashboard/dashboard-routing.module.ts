import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { DashboardLayoutComponent } from '../../layout/dashboard-layout/dashboard-layout.component';
import { OverviewComponent } from './pages/overview/overview.component';
import { AlertsComponent } from './pages/alerts/alerts.component';
import { MlModelsComponent } from './pages/ml-models/ml-models.component';
import { ImportDataComponent } from './pages/import-data/import-data.component';
import { PerformanceComponent } from './pages/performance/performance.component';
import { SimulationComponent } from './pages/simulation/simulation.component';
import { MessagesComponent } from './pages/messaging/messaging.component';
import { TrainDataComponent } from './pages/train-data/train-data.component';
import { DatasetsComponent } from './pages/datasets/datasets.component';

const routes: Routes = [
  {
    path: '',
    component: DashboardLayoutComponent,
    children: [
      { path: '', redirectTo: 'overview', pathMatch: 'full' },
      { path: 'overview', component: OverviewComponent },
      { path: 'alerts', component: AlertsComponent },
      { path: 'ml-models', component: MlModelsComponent },
      { path: 'import-data', component: ImportDataComponent },
      { path: 'performance', component: PerformanceComponent },
      { path: 'simulation', component: SimulationComponent },
      { path: 'messages', component: MessagesComponent },
      { path: 'training-data', component: TrainDataComponent },
      { path: 'datasets', component: DatasetsComponent },
      {
        path: 'clients-accounts',
        loadChildren: () => import('./pages/clients-accounts/clients-accounts.module').then(m => m.ClientsAccountsModule)
      },
      {
        path: 'administration',
        loadChildren: () => import('./pages/administration/administration.module').then(m => m.AdministrationModule)
      }
    ]
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class DashboardRoutingModule { }
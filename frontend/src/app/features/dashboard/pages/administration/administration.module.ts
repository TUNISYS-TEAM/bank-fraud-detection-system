import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AdministrationRoutingModule } from './administration-routing.module';
import { AdministrationComponent } from './administration.component';
import { UsersComponent } from './admin-sections/users/users.component';
import { DatasetsComponent } from './admin-sections/datasets/datasets.component';
import { LogsComponent } from './admin-sections/logs/logs.component';
import { SettingsComponent } from './admin-sections/settings/settings.component';
import { RolesComponent } from './admin-sections/roles/roles.component';
import { SessionsComponent } from './admin-sections/sessions/sessions.component';

@NgModule({
  declarations: [
    AdministrationComponent,
    UsersComponent,
    RolesComponent,
    SessionsComponent,
    DatasetsComponent,  
    LogsComponent,
    SettingsComponent
  ],
  imports: [
    CommonModule,
    AdministrationRoutingModule
  ]
})
export class AdministrationModule { }
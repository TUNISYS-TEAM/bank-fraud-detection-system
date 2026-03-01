import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AdministrationRoutingModule } from './administration-routing.module';
import { AdministrationComponent } from './administration.component';
import { UsersComponent } from './admin-sections/users/users.component';
import { DatasetsComponent } from './admin-sections/datasets/datasets.component';
import { LogsComponent } from './admin-sections/logs/logs.component';
import { SettingsComponent } from './admin-sections/settings/settings.component';


@NgModule({
  declarations: [
    AdministrationComponent,
    UsersComponent,
    /*RolesComponent,
    SessionsComponent,*/
    LogsComponent,
    SettingsComponent
  ],
  imports: [
    CommonModule,
    AdministrationRoutingModule
  ]
})
export class AdministrationModule { }
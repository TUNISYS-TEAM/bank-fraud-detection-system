import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule } from '@angular/forms';
import { FeatherModule } from 'angular-feather';
import { AdministrationRoutingModule } from './administration-routing.module';
import { AdministrationComponent } from './administration.component';
import { UsersComponent } from './admin-sections/users/users.component';
import { DatasetsComponent } from './admin-sections/datasets/datasets.component';
import { LogsComponent } from './admin-sections/logs/logs.component';
import { SettingsComponent } from './admin-sections/settings/settings.component';
import { RolesComponent } from './admin-sections/roles/roles.component';
import { SessionsComponent } from './admin-sections/sessions/sessions.component';

// Importer les modals standalone
import { UserCreateModalComponent } from '../../../../shared/modals/admin-sections/users/user-create-modal/user-create-modal.component';
import { UserUpdateModalComponent } from '../../../../shared/modals/admin-sections/users/user-update-modal/user-update-modal.component';
import { UserDeleteModalComponent } from '../../../../shared/modals/admin-sections/users/user-delete-modal/user-delete-modal.component';
import { UserViewModalComponent } from '../../../../shared/modals/admin-sections/users/user-view-modal/user-view-modal.component';


@NgModule({
  declarations: [
    AdministrationComponent,
    UsersComponent,
    DatasetsComponent,
    LogsComponent,
    SettingsComponent,
    RolesComponent,   
    SessionsComponent  
  ],
  imports: [
    CommonModule,
    ReactiveFormsModule,
    AdministrationRoutingModule,
    FeatherModule,
    // Importer les modals standalone
    UserCreateModalComponent,
    /*UserUpdateModalComponent,*/
    UserDeleteModalComponent,
    UserViewModalComponent

  ]
})
export class AdministrationModule { }
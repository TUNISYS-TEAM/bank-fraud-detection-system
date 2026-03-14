import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule } from '@angular/forms'; 
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
import { RoleCreateModalComponent } from '../../../../shared/modals/admin-sections/roles/role-create-modal/role-create-modal.component';
import { RoleUpdateModalComponent } from '../../../../shared/modals/admin-sections/roles/role-update-modal/role-update-modal.component';
import { RoleDeleteModalComponent } from '../../../../shared/modals/admin-sections/roles/role-delete-modal/role-delete-modal.component';
import { LogExportModalComponent } from '../../../../shared/modals/admin-sections/logs/log-export-modal/log-export-modal.component';
import { DatasetCreateModalComponent } from '../../../../shared/modals/admin-sections/datasets/dataset-create-modal/dataset-create-modal.component';
import { DatasetUpdateModalComponent } from '../../../../shared/modals/admin-sections/datasets/dataset-update-modal/dataset-update-modal.component';
import { DatasetDeleteModalComponent } from '../../../../shared/modals/admin-sections/datasets/dataset-delete-modal/dataset-delete-modal.component';
import { DatasetViewModalComponent } from '../../../../shared/modals/admin-sections/datasets/dataset-view-modal/dataset-view-modal.component';
import { ModalDeleteComponent } from '../../../../shared/modals/admin-sections/settings/settings-delete-modal/settings-delete-modal.component';
import { ModalViewRollbackComponent } from '../../../../shared/modals/admin-sections/settings/settings-rollback-modal/settings-rollback-modal.component';
import { CardsComponent } from '../../../../shared/cards/cards.component';
import { ClientsAccountsComponent } from './admin-sections/clients-accounts/clients-accounts.component';
import { ClientsViewModalComponent } from '../../../../shared/modals/admin-sections/clients-accounts/clients-view-modal/clients-view-modal.component';


@NgModule({
  declarations: [
    AdministrationComponent,
    UsersComponent,
    DatasetsComponent,
    LogsComponent,
    SettingsComponent,
    RolesComponent,   
    SessionsComponent, 
    ClientsAccountsComponent  
  ],
  imports: [
    CommonModule,
    FormsModule, 
    ReactiveFormsModule,
    AdministrationRoutingModule,
    FeatherModule,
    //User
    UserCreateModalComponent,
    UserUpdateModalComponent,
    UserDeleteModalComponent,
    UserViewModalComponent,
    // Roles
    RoleCreateModalComponent,   
    RoleUpdateModalComponent,
    RoleDeleteModalComponent,
    LogExportModalComponent,
    //Datasets
    DatasetCreateModalComponent,
    DatasetUpdateModalComponent,
    DatasetDeleteModalComponent,
    DatasetViewModalComponent, 

    ModalDeleteComponent,
    ModalViewRollbackComponent,

    ClientsViewModalComponent,
    
    CardsComponent
  ]
})
export class AdministrationModule { }
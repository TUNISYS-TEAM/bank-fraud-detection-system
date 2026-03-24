import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { FeatherModule } from 'angular-feather';
import { CardsComponent } from '../../../../shared/cards/cards.component';
import { AdministrationRoutingModule } from './administration-routing.module';
import { AdministrationComponent } from './administration.component';
import { UsersComponent } from './admin-sections/users/users.component';

import { LogsComponent } from './admin-sections/logs/logs.component';
import { SettingsComponent } from './admin-sections/settings/settings.component';
import { SessionsComponent } from './admin-sections/sessions/sessions.component';
import { RolesComponent } from './admin-sections/roles/roles.component';
import { UserCreateModalComponent } from '../../../../shared/modals/admin-sections/users/user-create-modal/user-create-modal.component';
import { UserDeleteModalComponent } from '../../../../shared/modals/admin-sections/users/user-delete-modal/user-delete-modal.component';
import { UserUpdateModalComponent } from '../../../../shared/modals/admin-sections/users/user-update-modal/user-update-modal.component';
import { UserViewModalComponent } from '../../../../shared/modals/admin-sections/users/user-view-modal/user-view-modal.component';
import { RoleCreateModalComponent } from '../../../../shared/modals/admin-sections/roles/role-create-modal/role-create-modal.component';
import { RoleUpdateModalComponent } from '../../../../shared/modals/admin-sections/roles/role-update-modal/role-update-modal.component';
import { RoleDeleteModalComponent } from '../../../../shared/modals/admin-sections/roles/role-delete-modal/role-delete-modal.component';
import { LogExportModalComponent } from '../../../../shared/modals/admin-sections/logs/log-export-modal/log-export-modal.component';
import { ModalDeleteComponent } from '../../../../shared/modals/admin-sections/settings/settings-delete-modal/settings-delete-modal.component';
import { ModalViewRollbackComponent } from '../../../../shared/modals/admin-sections/settings/settings-rollback-modal/settings-rollback-modal.component';


@NgModule({
  declarations: [
    AdministrationComponent,
    UsersComponent,
    LogsComponent,
    SettingsComponent,
    SessionsComponent,
    RolesComponent
  ],
  imports: [
    CommonModule,
    AdministrationRoutingModule,
    FeatherModule,
    UserCreateModalComponent,
    UserDeleteModalComponent,
    UserUpdateModalComponent,
    UserViewModalComponent,
    LogExportModalComponent,
    ModalDeleteComponent,
    ModalViewRollbackComponent,
    RoleCreateModalComponent,
    RoleUpdateModalComponent,
    RoleDeleteModalComponent,
    CardsComponent,
    FormsModule,
    ReactiveFormsModule
  ]
})
export class AdministrationModule { }
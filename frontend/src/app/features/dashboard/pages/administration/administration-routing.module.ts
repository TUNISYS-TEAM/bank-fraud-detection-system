import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { AdministrationComponent } from './administration.component';
import { UsersComponent } from './admin-sections/users/users.component';

import { LogsComponent } from './admin-sections/logs/logs.component';
import { SettingsComponent } from './admin-sections/settings/settings.component';
import { permissionGuard } from '../../../../core/guards/auth.guard';
import { RolesComponent } from './admin-sections/roles/roles.component';
import { SessionsComponent } from './admin-sections/sessions/sessions.component';


const routes: Routes = [
  {
    path: '',
    component: AdministrationComponent,
    canActivate: [permissionGuard],
    data: {
      permission: 'ACCESS_ADMINISTRATION',
      permissionFailRedirect: '/dashboard/overview'
    },
    children: [
      { path: '', redirectTo: 'users', pathMatch: 'full' },

      {
        path: 'users',
        component: UsersComponent,
        canActivate: [permissionGuard],
        data: {
          permission: 'MANAGE_USERS',
          permissionFailRedirect: '/dashboard/administration'
        }
      },

      {
        path: 'roles',
        component: RolesComponent,
        canActivate: [permissionGuard],
        data: {
          permission: 'MANAGE_ROLES',
          permissionFailRedirect: '/dashboard/administration'
        }
      },

      {
        path: 'sessions',
        component: SessionsComponent,
        canActivate: [permissionGuard],
        data: {
          permission: 'MANAGE_SESSIONS',
          permissionFailRedirect: '/dashboard/administration'
        }
      },



      {
        path: 'logs',
        component: LogsComponent,
        canActivate: [permissionGuard],
        data: {
          permission: 'MANAGE_LOGS',
          permissionFailRedirect: '/dashboard/administration'
        }
      },

      {
        path: 'settings',
        component: SettingsComponent,
        canActivate: [permissionGuard],
        data: {
          permission: 'MANAGE_SETTINGS',
          permissionFailRedirect: '/dashboard/administration'
        }
      }
    ]
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class AdministrationRoutingModule { }
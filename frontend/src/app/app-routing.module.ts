import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { authGuard, adminGuard, permissionGuard } from './core/guards/auth.guard';

const routes: Routes = [
  {
    path: '',
    redirectTo: '/dashboard/overview',
    pathMatch: 'full'
  },
  {
    path: 'auth',
    loadChildren: () => import('./features/auth/auth.module').then(m => m.AuthModule)
  },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadChildren: () => import('./features/dashboard/dashboard.module').then(m => m.DashboardModule)
  },
  {
    path: '**',
    redirectTo: '/dashboard/overview'
  }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
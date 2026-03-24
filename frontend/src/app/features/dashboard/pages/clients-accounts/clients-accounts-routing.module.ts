import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { ClientsAccountsComponent } from './clients-accounts.component';
import { ListeDetailsComponent } from './clients-accounts-sections/liste-details/liste-details.component';
import { ClientsComponent } from './clients-accounts-sections/clients/clients.component';
import { AccountsComponent } from './clients-accounts-sections/accounts/accounts.component';

const routes: Routes = [
  {
    path: '',
    component: ClientsAccountsComponent,
    children: [
      { path: 'liste-details', component: ListeDetailsComponent },
      { path: 'clients', component: ClientsComponent },
      { path: 'accounts', component: AccountsComponent },
      { path: '', redirectTo: 'liste-details', pathMatch: 'full' }
    ]
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class ClientsAccountsRoutingModule { }
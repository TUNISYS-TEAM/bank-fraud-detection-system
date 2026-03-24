import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FeatherModule } from 'angular-feather';
import { RouterModule } from '@angular/router';
import { ClientsAccountsRoutingModule } from './clients-accounts-routing.module';
import { ClientsAccountsComponent } from './clients-accounts.component';
import { ListeDetailsComponent } from './clients-accounts-sections/liste-details/liste-details.component';
import { ClientsComponent } from './clients-accounts-sections/clients/clients.component';
import { AccountsComponent } from './clients-accounts-sections/accounts/accounts.component';
import { CardsComponent } from '../../../../shared/cards/cards.component';
import { ClientsViewModalComponent } from '../../../../shared/modals/clients-accounts/clients-view-modal/clients-view-modal.component';

@NgModule({
  declarations: [
    ClientsAccountsComponent,
    ListeDetailsComponent,
    ClientsComponent,
    AccountsComponent
  ],
  imports: [
    CommonModule,
    RouterModule,
    ClientsAccountsRoutingModule,
    CardsComponent,
    FormsModule,
    FeatherModule,
    ClientsViewModalComponent
  ]
})
export class ClientsAccountsModule { }
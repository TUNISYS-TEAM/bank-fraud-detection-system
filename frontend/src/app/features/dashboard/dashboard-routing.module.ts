import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { HelloTestComponent } from './hello-test/hello-test.component';

const routes: Routes = [
  { path: '', component: HelloTestComponent }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class DashboardRoutingModule { }
import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule, HTTP_INTERCEPTORS } from '@angular/common/http';
import { ReactiveFormsModule } from '@angular/forms';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { AuthInterceptor } from './core/interceptors/auth.interceptor';

import { FeatherModule } from 'angular-feather';
import {
  Shield, Grid, Upload, Globe, AlertTriangle, Activity,
  ExternalLink, Settings, LogOut, Bell, X, Mail, Lock,
  Eye, EyeOff, Users, Database, FileText, Plus, Download, 
  CheckCircle, PlusCircle, BarChart2, PlayCircle, Cpu, Info, Clock,
  AlertOctagon, Sliders, Key, Edit2, Trash2, ChevronsLeft, ChevronsRight, ChevronLeft, ChevronRight,
  Power, RefreshCw, Copy, Chrome, 
} from 'angular-feather/icons';
import { CardsComponent } from './shared/cards/cards.component';
import { UserViewModalComponent } from './shared/modals/admin-sections/users/user-view-modal/user-view-modal.component';


const icons = {
  Shield, Grid, Upload, Globe, AlertTriangle, Activity,
  ExternalLink, Settings, LogOut, Bell, X, Mail, Lock,
  Eye, EyeOff, Users, Database, FileText, Plus, Download,
  CheckCircle, PlusCircle, BarChart2, PlayCircle, Cpu, RefreshCw, Info, Power, Copy, Chrome,
  AlertOctagon, Sliders, Clock, Key, Edit2, Trash2, ChevronsLeft, ChevronsRight, ChevronLeft, ChevronRight 
};

@NgModule({
  declarations: [
    AppComponent,
    CardsComponent,
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    HttpClientModule,
    ReactiveFormsModule, 
    FeatherModule.pick(icons)

  ],
  providers: [
    {
      provide: HTTP_INTERCEPTORS,
      useClass: AuthInterceptor,
      multi: true
    }
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
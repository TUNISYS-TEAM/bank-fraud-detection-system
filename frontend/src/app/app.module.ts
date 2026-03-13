// app.module.ts
import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule, HttpClientXsrfModule, HTTP_INTERCEPTORS } from '@angular/common/http';
import { ReactiveFormsModule } from '@angular/forms';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { AuthInterceptor } from './core/interceptors/auth.interceptor';

import { FeatherModule } from 'angular-feather';
import {
  Shield, Grid, Upload, Globe, AlertTriangle, Activity,
  ExternalLink, Settings, LogOut, Bell, X, Mail, Lock, HelpCircle, TrendingUp, Play,
  Eye, EyeOff, Users, Database, FileText, Plus, Download, Target, Hash, HardDrive,
  CheckCircle, PlusCircle, BarChart2, PlayCircle, Cpu, Info, Clock, List, Loader,
  AlertOctagon, Sliders, Key, Edit2, Trash2, ChevronsLeft, ChevronsRight, ChevronLeft, ChevronRight,
  Power, RefreshCw, Copy, Chrome, Search, Tag, Calendar, AlertCircle,
} from 'angular-feather/icons';

const icons = {
  Shield, Grid, Upload, Globe, AlertTriangle, Activity,
  ExternalLink, Settings, LogOut, Bell, X, Mail, Lock, Tag, Calendar, Target, Hash, HardDrive, List,
  Eye, EyeOff, Users, Database, FileText, Plus, Download, Search, AlertCircle, HelpCircle, TrendingUp, Play,
  CheckCircle, PlusCircle, BarChart2, PlayCircle, Cpu, RefreshCw, Info, Power, Copy, Chrome, Loader,
  AlertOctagon, Sliders, Clock, Key, Edit2, Trash2, ChevronsLeft, ChevronsRight, ChevronLeft, ChevronRight 
};

@NgModule({
  declarations: [
    AppComponent,
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    HttpClientModule,
    ReactiveFormsModule, 
    FeatherModule.pick(icons),

    HttpClientXsrfModule.withOptions({
      cookieName: 'XSRF-TOKEN',
      headerName: 'X-XSRF-TOKEN',
    }),
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
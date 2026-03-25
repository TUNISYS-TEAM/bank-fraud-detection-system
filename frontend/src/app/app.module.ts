import { NgModule, LOCALE_ID } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule, HttpClientXsrfModule, HTTP_INTERCEPTORS } from '@angular/common/http';
import { ReactiveFormsModule } from '@angular/forms';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { AuthInterceptor } from './core/interceptors/auth.interceptor';

import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
registerLocaleData(localeFr);

import { FeatherModule } from 'angular-feather';
import {
  Shield, Grid, Upload, Globe, AlertTriangle, Activity,
  ExternalLink, Settings, LogOut, Bell, X, Mail, Lock, HelpCircle, TrendingUp, Play,
  Eye, EyeOff, Users, Database, FileText, Plus, Download, Target, Hash, HardDrive,
  CheckCircle, PlusCircle, BarChart2, PlayCircle, Cpu, Info, Clock, List, Loader,
  AlertOctagon, Sliders, Key, Edit2, Trash2, ChevronsLeft, ChevronsRight, ChevronLeft, ChevronRight,
  Power, RefreshCw, Copy, Chrome, Search, Tag, Calendar, AlertCircle,
  GitMerge,
  Zap,
  Layers,
  CheckSquare,
  Square,
  ChevronUp,
  CreditCard,
  ChevronDown,
  Check,
  RotateCcw,
  Award,
  XCircle,
  PieChart,
  DollarSign,
  UserCheck,
  Server,
} from 'angular-feather/icons';
const icons = {
  Shield, Grid, Upload, Globe, AlertTriangle, Activity,
  ExternalLink, Settings, LogOut, Bell, X, Mail, Lock, Tag, Calendar, Target, Hash, HardDrive, List,
  Eye, EyeOff, Users, Database, FileText, Plus, Download, Search, AlertCircle, HelpCircle, TrendingUp, Play,
  CheckCircle, PlusCircle, BarChart2, PlayCircle, Cpu, RefreshCw, Info, Power, Copy, Chrome, Loader, GitMerge, Zap,
  AlertOctagon, Sliders, Clock, Key, Edit2, Trash2, ChevronsLeft, ChevronsRight, ChevronLeft, ChevronRight, Layers, Server,
  CheckSquare, Square, ChevronUp, CreditCard, ChevronDown, Check, RotateCcw, Award, XCircle, PieChart, DollarSign, UserCheck
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
    },
    { provide: LOCALE_ID, useValue: 'fr-FR' }

  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
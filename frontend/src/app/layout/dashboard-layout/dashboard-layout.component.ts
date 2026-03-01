import { Component, OnInit } from '@angular/core';

@Component({
  selector: 'app-dashboard-layout',
  templateUrl: './dashboard-layout.component.html',
  styleUrl: './dashboard-layout.component.css'
})
export class DashboardLayoutComponent implements OnInit {

  ngOnInit(): void {
    if ((window as any).require) {
      const { ipcRenderer } = (window as any).require('electron');
      ipcRenderer.send('maximize-window');
    }
  }

}
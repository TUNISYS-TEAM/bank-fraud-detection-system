import { Component, EventEmitter, inject, Input, OnInit, Output, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ClientDetail, ClientService } from '../../../../core/services/client.service';

@Component({
  selector: 'app-clients-view-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './clients-view-modal.component.html',
  styleUrl: './clients-view-modal.component.css'
})
export class ClientsViewModalComponent implements OnInit {
  @Input({ required: true }) clientId!: number;
  @Output() close = new EventEmitter<void>();

  private clientService = inject(ClientService);
  
  client = signal<any>(null);
  isLoading = signal<boolean>(false);
  activeTab = signal<string>('overview');

  totalBalance = computed(() => {
    const c = this.client();
    if (!c || !c.accounts) return 0;
    return c.accounts.reduce((sum: number, acc: any) => sum + (acc.balance || 0), 0);
  });

  totalAccounts = computed(() => this.client()?.accounts?.length || 0);

  overallRisk = computed(() => {
    const c = this.client();
    if (!c || !c.accounts || c.accounts.length === 0) return 'LOW';
    const risks = c.accounts.map((a: any) => a.riskLevel?.toUpperCase());
    if (risks.includes('HIGH')) return 'HIGH';
    if (risks.includes('MEDIUM')) return 'MEDIUM';
    return 'LOW';
  });

  ngOnInit(): void {
    this.loadClientDetails();
  }

  loadClientDetails(): void {
    this.isLoading.set(true);
    this.clientService.getClientById(this.clientId).subscribe({
      next: (data: ClientDetail) => {
        this.client.set(data);
        this.isLoading.set(false);
      },
      error: (err: Error) => {
        console.error('Error loading client details:', err);
        this.isLoading.set(false);
      }
    });
  }

  onClose(): void {
    this.close.emit();
  }

  setTab(tab: string): void {
    this.activeTab.set(tab);
  }
}
import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { ClientService, ClientSummary, PageResponse } from '../../../../../../core/services/client.service';
import { AuthStore } from '../../../../../../core/store/auth.store';

@Component({
  selector: 'app-clients-accounts',
  templateUrl: './clients-accounts.component.html',
  styleUrl: './clients-accounts.component.css'
})
export class ClientsAccountsComponent implements OnInit {
  private clientService = inject(ClientService);
  authStore = inject(AuthStore);

  clients = signal<ClientSummary[]>([]);
  isLoading = signal(false);
  
  // Pagination
  currentPage = signal(0);
  totalElements = signal(0);
  totalPages = signal(0);
  pageSize = 20;

  // Search
  searchQuery = signal('');

  // Modals
  showViewModal = signal(false);
  selectedClientId = signal<number | null>(null);

  ngOnInit(): void {
    this.loadClients();
  }

  loadClients(): void {
    this.isLoading.set(true);
    if (this.searchQuery()) {
      this.clientService.searchClients(this.searchQuery(), this.currentPage(), this.pageSize).subscribe({
        next: (response: PageResponse<ClientSummary>) => {
          this.clients.set(response.content);
          this.totalElements.set(response.totalElements);
          this.totalPages.set(response.totalPages);
          this.isLoading.set(false);
        },
        error: (err: Error) => {
          console.error('Error searching clients:', err);
          this.isLoading.set(false);
        }
      });
    } else {
      this.clientService.getClients(this.currentPage(), this.pageSize).subscribe({
        next: (response: PageResponse<ClientSummary>) => {
          this.clients.set(response.content);
          this.totalElements.set(response.totalElements);
          this.totalPages.set(response.totalPages);
          this.isLoading.set(false);
        },
        error: (err: Error) => {
          console.error('Error loading clients:', err);
          this.isLoading.set(false);
        }
      });
    }
  }

  onSearch(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.searchQuery.set(input.value);
    this.currentPage.set(0);
    this.loadClients();
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadClients();
  }

  openViewModal(clientId: number): void {
    this.selectedClientId.set(clientId);
    this.showViewModal.set(true);
  }

  closeViewModal(): void {
    this.showViewModal.set(false);
    this.selectedClientId.set(null);
  }

  displayRange = computed(() => {
    const start = this.currentPage() * this.pageSize + 1;
    const end = Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());
    return { start, end };
  });

  getPageNumbers(): number[] {
    const total = this.totalPages();
    if (total <= 7) {
      return Array.from({ length: total }, (_, i) => i + 1);
    }
    
    const pages: number[] = [];
    const current = this.currentPage() + 1;
    
    if (current <= 4) {
      pages.push(1, 2, 3, 4, 5, -1, total);
    } else if (current >= total - 3) {
      pages.push(1, -1, total - 4, total - 3, total - 2, total - 1, total);
    } else {
      pages.push(1, -1, current - 1, current, current + 1, -1, total);
    }
    
    return pages;
  }
}
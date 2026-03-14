import { Component, EventEmitter, inject, Input, OnInit, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ClientDetail, ClientService } from '../../../../../core/services/client.service';

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
  
  client = signal<ClientDetail | null>(null);
  isLoading = signal(true);

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
}
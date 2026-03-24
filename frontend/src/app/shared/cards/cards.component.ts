import { Component, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FeatherModule } from 'angular-feather';

export interface KpiCard {
  label: string;
  value: string | number;
  icon: string;
  trend?: string;
  trendUp?: boolean;
  description?: string;
  color?: string;
  isCurrency?: boolean;
}

@Component({
  selector: 'app-cards',
  standalone: true,
  imports: [CommonModule, FeatherModule],
  templateUrl: './cards.component.html',
  styleUrl: './cards.component.css'
})
export class CardsComponent {
  data = input.required<KpiCard>();
}
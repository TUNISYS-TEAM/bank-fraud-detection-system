import { Component, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FeatherModule } from 'angular-feather';

export interface KpiCard {
  label: string;
  value: string | number;
  icon: string;
  trend?: string;
  trendIcon?: string;
  trendUp?: boolean;
  isStable?: boolean;
  reverseTrend?: boolean;
  description?: string;
  color?: string;
  isCurrency?: boolean;
  abbreviate?: boolean;
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

  formatValue(value: any, isCurrency: boolean = false): string {
    if (value === null || value === undefined || value === '...') return value ?? '...';

    const valString = String(value);
    
    // If abbreviation is NOT requested, we use standard formatting
    if (!this.data().abbreviate) {
      if (typeof value === 'number' || !isNaN(Number(valString))) {
        const numValue = Number(valString);
        if (isCurrency) {
          return numValue.toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' TND';
        }
        return numValue.toLocaleString('fr-FR', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
      }
      return valString;
    }

    // Abbreviation logic (only if abbreviate is true)
    if (valString.endsWith('m') || valString.endsWith('h') || valString.endsWith('s') || valString.includes('%')) {
      return valString;
    }

    let num = typeof value === 'number' ? value : parseFloat(valString.replace(/[^0-9.-]+/g, ""));
    if (isNaN(num)) return valString;

    const abs = Math.abs(num);
    let result = '';
    let suffix = '';

    if (abs >= 1000000000) {
      result = (num / 1000000000).toLocaleString('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
      suffix = 'B';
    } else if (abs >= 1000000) {
      result = (num / 1000000).toLocaleString('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
      suffix = 'M';
    } else if (abs >= 1000) {
      result = (num / 1000).toLocaleString('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
      suffix = 'K';
    } else {
      if (isCurrency) {
        return num.toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' TND';
      }
      return num.toLocaleString('fr-FR', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
    }

    // Nettoyage de la virgule décimale française si elle est à zéro (ex: 109,0M -> 109M)
    if (result.endsWith(',0')) {
      result = result.substring(0, result.length - 2);
    }

    const finalVal = `${result}${suffix}`;
    return isCurrency ? `${finalVal} TND` : finalVal;
  }
}
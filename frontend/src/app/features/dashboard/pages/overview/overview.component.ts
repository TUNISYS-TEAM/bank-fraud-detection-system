import { Component, inject, computed } from '@angular/core';
import { AuthStore } from '../../../../core/store/auth.store';

@Component({
  selector: 'app-overview',
  templateUrl: './overview.component.html',
  styleUrl: './overview.component.css'
})
export class OverviewComponent {
  readonly authStore = inject(AuthStore);

  // Mapping des libellés de rôle
  roleDisplay = computed(() => this.authStore.userRoleLabel());

  // Description dynamique selon le rôle
  roleDescription = computed(() => {
    const role = this.authStore.userRole();
    switch (role) {
      case 'ADMIN': return 'Vous avez un contrôle total sur la configuration et la gestion du système.';
      case 'ANALYST': return 'Utilisez nos outils avancés pour détecter et analyser les tentatives de fraude.';
      case 'MANAGER': return 'Supervisez la qualité des modèles et validez les stratégies de détection.';
      default: return 'Bienvenue sur votre plateforme de surveillance.';
    }
  });

  // Liste des fonctionnalités
  features = computed(() => {
    const role = this.authStore.userRole();
    if (role === 'ADMIN') {
      return [
        'Configuration globale des services métier',
        'Gestion complète des comptes utilisateurs',
        'Audit et consultation des journaux (logs)',
        'Gestion de l\'infrastructure des datasets'
      ];
    } else if (role === 'MANAGER') {
      return [
        'Validation et mise en production des modèles',
        'Supervision stratégique des performances',
        'Analyse du contexte des règles de détection',
        'Évaluation et optimisation du moteur de détection'
      ];
    } else {
      return [
        'Importation et filtrage des transactions',
        'Entraînement et test des modèles de détection',
        'Analyse et validation des alertes suspectes',
        'Simulation de scénarios de fraude complexes'
      ];
    }
  });
}
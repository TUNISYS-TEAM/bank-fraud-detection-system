═══════════════════════════════════════════════════════════
PARTIE I — INFORMATIONS GÉNÉRALES
═══════════════════════════════════════════════════════════

Chapitre 1 : Présentation du guide
  1.1 Objectif du document
  1.2 Public cible
  1.3 Périmètre fonctionnel couvert
  1.4 Organisation du guide
  1.5 Conventions typographiques

Chapitre 2 : Accès à la plateforme
  2.1 Prérequis d'accès
      2.1.1 Navigateurs internet supportés
      2.1.2 Configuration réseau et accès VPN requis
  2.2 URL d'accès aux services
  2.3 Authentification et connexion
      2.3.1 Procédure de première connexion
      2.3.2 Scénario A — Mot de passe provisoire
      2.3.3 Scénario B — Mot de passe auto-généré
      2.3.4 Changement du mot de passe après connexion
  2.4 Gestion des sessions utilisateur
  2.5 Déconnexion

═══════════════════════════════════════════════════════════
PARTIE II — INTERFACE COMMUNE À TOUS LES UTILISATEURS
═══════════════════════════════════════════════════════════

Chapitre 3 : Vue d'ensemble de l'interface principale
  3.1 Présentation générale du tableau de bord
  3.2 Navigation principale (sidebar et top bar)
  3.3 Indicateurs clés de performance (KPIs)
  3.4 Menu utilisateur et notifications
      3.4.1 Centre de notifications (cloche)
      3.4.2 Historique des notifications (horloge)

Chapitre 4 : Gestion du profil utilisateur
  4.1 Consultation des informations du compte
  4.2 Modification des informations personnelles
  4.3 Sécurité et changement du mot de passe

Chapitre 5 : Messagerie interne collaborative
  5.1 Présentation du module Messages
  5.2 Boîte de réception
  5.3 Envoi d'un nouveau message
  5.4 Messages envoyés
  5.5 Archivage des messages

═══════════════════════════════════════════════════════════
PARTIE III — GUIDE DE L'ADMINISTRATEUR
═══════════════════════════════════════════════════════════

Chapitre 6 : Tableau de bord Administrateur
  6.1 Vue d'ensemble technique du système
  6.2 Métriques d'infrastructure (Uptime, CPU, Mémoire)
  6.3 Santé globale et stabilité
  6.4 Suivi des fraudes confirmées

Chapitre 7 : Administration du système
  7.1 Présentation du module Administration
  7.2 Gestion des utilisateurs
      7.2.1 Consultation de la liste des utilisateurs
      7.2.2 Création d'un nouvel utilisateur
      7.2.3 Modification et suppression d'un utilisateur
  7.3 Gestion des rôles et permissions
      7.3.1 Consultation des rôles système
      7.3.2 Création d'un nouveau rôle
      7.3.3 Attribution et révocation des permissions
  7.4 Suivi des sessions utilisateurs
      7.4.1 Sessions actives et terminées
      7.4.2 Terminaison forcée d'une session
  7.5 Journaux d'activité (Logs)
      7.5.1 Consultation et filtrage des journaux
      7.5.2 Export des logs
  7.6 Paramètres système
      7.6.1 Sécurité (expiration session, journalisation)
      7.6.2 Configuration système (rétention, sauvegarde)
      7.6.3 Préférences de notifications
      7.6.4 Data & Rollback (sauvegardes et restauration)

Chapitre 8 : Gestion des données bancaires
  8.1 Gestion des datasets
      8.1.1 Consultation de la liste des datasets
      8.1.2 Import d'un nouveau dataset
      8.1.3 Visualisation et suppression
  8.2 Gestion des clients et comptes
      8.2.1 Liste des clients
      8.2.2 Analytique clients (statistiques démographiques)
      8.2.3 Analytique comptes (état des portefeuilles)
  8.3 Consultation des transactions analysées
      8.3.1 Analyse des flux et scoring
      8.3.2 Historique des analyses

═══════════════════════════════════════════════════════════
PARTIE IV — GUIDE DU RESPONSABLE DU RISQUE (MANAGER)
═══════════════════════════════════════════════════════════

Chapitre 9 : Tableau de bord Responsable du Risque
  9.1 Vue d'ensemble métier et stratégique
  9.2 Indicateurs clés (Alertes, Taux de Fraude, Modèle Actif)
  9.3 Analyse prédictive et profilage de risque
  9.4 Cartographie géographique des fraudes
  9.5 Moteur Prédictif IA

Chapitre 10 : Gestion des données d'entraînement
  10.1 Consultation des datasets d'entraînement
  10.2 Import d'un nouveau dataset
  10.3 Qualification des datasets de validation
  10.4 Export des données

Chapitre 11 : Supervision des alertes de fraude
  11.1 Vue d'ensemble des alertes
  11.2 Vue des alertes
      11.2.1 Recherche et filtrage
      11.2.2 Export des alertes
  11.3 Centre d'affectation
      11.3.1 Vue d'ensemble de la distribution
      11.3.2 Gestion de l'équipe d'analystes
      11.3.3 Affectation des alertes non assignées

Chapitre 12 : Évaluation des performances des modèles ML
  12.1 Indicateurs clés des modèles
  12.2 Bibliothèque de modèles
  12.3 Sélection multi-modèles pour comparaison
  12.4 Analyse comparative
      12.4.1 Profil de performance (radar)
      12.4.2 Métriques de score
      12.4.3 Courbes ROC comparées
      12.4.4 Optimisation du seuil
      12.4.5 Matrices de confusion
  12.5 Comparaison détaillée
  12.6 Surveillance de la dérive du modèle (Model Drift)
      12.6.1 Drift de distribution
      12.6.2 Drift de performance
      12.6.3 Prévision de dérive
      12.6.4 Plan d'action recommandé

Chapitre 13 : Validation des modèles ML
  13.1 Réception des demandes de validation
  13.2 Analyse des détails du modèle
  13.3 Approbation et déploiement
  13.4 Rejet d'un modèle

Chapitre 14 : Reporting managérial
  14.1 Consultation des rapports reçus
  14.2 Analyse des rapports d'activité

═══════════════════════════════════════════════════════════
PARTIE V — GUIDE DE L'ANALYSTE DE FRAUDE
═══════════════════════════════════════════════════════════

Chapitre 15 : Tableau de bord Analyste
  15.1 Vue d'ensemble opérationnelle
  15.2 Indicateurs métier
  15.3 Dernières anomalies et actions rapides
  15.4 Statistiques système

Chapitre 16 : Importation et préparation des données
  16.1 Sélection d'un dataset traité
  16.2 Analyse de la structure des colonnes
  16.3 Évaluation de la qualité des données

Chapitre 17 : Entraînement des modèles de Machine Learning
  17.1 Sélection de l'algorithme
  17.2 Configuration des paramètres du modèle
  17.3 Sélection du dataset et des variables
  17.4 Entraînement et sauvegarde
  17.5 Consultation de l'historique des versions
  17.6 Soumission d'un modèle pour validation

Chapitre 18 : Traitement des alertes de fraude
  18.1 Consultation des alertes assignées
  18.2 Investigation d'une alerte
      18.2.1 Détail de la transaction
      18.2.2 Analyse du risque (XAI)
      18.2.3 Ajout de commentaires
  18.3 Prise de décision
      18.3.1 Validation d'une fraude
      18.3.2 Rejet d'une alerte (faux positif)
      18.3.3 Marquage comme vue
  18.4 Consultation des alertes traitées

Chapitre 19 : Analyse comparative des performances
  19.1 Indicateurs clés des modèles
  19.2 Sélection et comparaison de modèles
  19.3 Visualisations comparatives
  19.4 Matrices de confusion

Chapitre 20 : Simulation de scénarios de fraude
  20.1 Configuration d'un scénario
      20.1.1 Sélection du modèle à tester
      20.1.2 Paramétrage du scénario
      20.1.3 Distribution temporelle
  20.2 Lancement de la simulation
  20.3 Analyse des résultats
      20.3.1 Onglet Performance
      20.3.2 Onglet Échantillonnage
      20.3.3 Onglet Métriques Modèle
  20.4 Analyse comparative & Drift Performance
      20.4.1 Mouvement F1-Score
      20.4.2 Index de robustesse

Chapitre 21 : Génération de rapports d'activité
  21.1 Création d'un nouveau rapport
  21.2 Sélection des destinataires
  21.3 Configuration de la période
  21.4 Envoi et suivi

═══════════════════════════════════════════════════════════
PARTIE VI — ANNEXES
═══════════════════════════════════════════════════════════

Chapitre 22 : Troubleshooting et FAQ
  22.1 Problèmes courants d'accès et de session
  22.2 Problèmes de performance
  22.3 Foire aux questions
  22.4 Contacts et support
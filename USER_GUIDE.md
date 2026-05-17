# Table des matières

- [Chapitre 1 : Présentation du guide](#chapitre-1--présentation-du-guide)
  - [1.1 Objectif du document](#11-objectif-du-document)
  - [1.2 Public cible](#12-public-cible)
  - [1.3 Périmètre fonctionnel couvert](#13-périmètre-fonctionnel-couvert)
  - [1.4 Organisation du guide](#14-organisation-du-guide)
- [Chapitre 2 : Accès à la plateforme](#chapitre-2--accès-à-la-plateforme)
  - [2.1 Prérequis d'accès](#21-prérequis-daccès)
    - [2.1.1 Navigateurs internet supportés](#211-navigateurs-internet-supportés)
    - [2.1.2 Configuration réseau et accès VPN requis](#212-configuration-réseau-et-accès-vpn-requis)
  - [2.2 URL d'accès aux services](#22-url-daccès-aux-services)
  - [2.3 Authentification et connexion](#23-authentification-et-connexion)
    - [2.3.1 Procédure de première connexion](#231-procédure-de-première-connexion)
    - [2.3.2 Validation du compte et vérification de l'e-mail par code OTP](#232-validation-du-compte-et-vérification-de-le-mail-par-code-otp)
  - [2.4 Gestion des sessions utilisateur](#24-gestion-des-sessions-utilisateur)
  - [2.5 Déconnexion](#25-déconnexion)
- [Chapitre 3 : Vue d'ensemble de l'interface principale](#chapitre-3--vue-densemble-de-linterface-principale)
  - [3.1 Présentation générale du tableau de bord](#31-présentation-générale-du-tableau-de-bord)
  - [3.2 Navigation principale](#32-navigation-principale)
  - [3.3 Indicateurs clés de performance (KPIs)](#33-indicateurs-clés-de-performance-kpis)
  - [3.4 Menu utilisateur et profil](#34-menu-utilisateur-et-profil)
- [Chapitre 4 : Gestion des alertes de fraude](#chapitre-4--gestion-des-alertes-de-fraude)
  - [4.1 Réception et affichage des alertes](#41-réception-et-affichage-des-alertes)
  - [4.2 Triage et affectation](#42-triage-et-affectation)
  - [4.3 Investigation et décision](#43-investigation-et-décision)
  - [4.4 Workflow collaboratif et escalade](#44-workflow-collaboratif-et-escalade)
- [Chapitre 5 : Analyse des transactions](#chapitre-5--analyse-des-transactions)
  - [5.1 Recherche et filtrage de transactions](#51-recherche-et-filtrage-de-transactions)
  - [5.2 Détails et historique d'une transaction](#52-détails-et-historique-dune-transaction)
  - [5.3 Graphe de relations entre transactions](#53-graphe-de-relations-entre-transactions)
- [Chapitre 6 : Scoring et interprétabilité des modèles ML](#chapitre-6--scoring-et-interprétabilité-des-modèles-ml)
  - [6.1 Comprendre les scores de risque](#61-comprendre-les-scores-de-risque)
  - [6.2 Explicabilité des modèles (SHAP, Deep Learning)](#62-explicabilité-des-modèles-shap-deep-learning)
  - [6.3 Comparaison multi-modèles](#63-comparaison-multi-modèles)
- [Chapitre 7 : Rapports et statistiques](#chapitre-7--rapports-et-statistiques)
  - [7.1 Générer un rapport d'activité](#71-générer-un-rapport-dactivité)
  - [7.2 Export de données à fins réglementaires](#72-export-de-données-à-fins-réglementaires)
  - [7.3 Tableaux de bord statistiques](#73-tableaux-de-bord-statistiques)
- [Chapitre 8 : Administration et gestion des accès](#chapitre-8--administration-et-gestion-des-accès)
  - [8.1 Gestion des utilisateurs et des rôles](#81-gestion-des-utilisateurs-et-des-rôles)
  - [8.2 Configuration des paramètres système](#82-configuration-des-paramètres-système)
  - [8.3 Audit et traçabilité des actions](#83-audit-et-traçabilité-des-actions)
- [Chapitre 9 : Troubleshooting et FAQ](#chapitre-9--troubleshooting-et-faq)
  - [9.1 Problèmes courants d'accès et de session](#91-problèmes-courants-daccès-et-de-session)
  - [9.2 Problèmes de performance](#92-problèmes-de-performance)
  - [9.3 Contacts et support](#93-contacts-et-support)

---

# Chapitre 1 : Présentation du guide

## 1.1 Objectif du document

Ce guide a pour but de fournir une documentation fonctionnelle complète pour la prise en main et l'utilisation quotidienne de la plateforme de détection de fraude bancaire. Il est conçu pour accompagner les utilisateurs dans l'analyse des transactions, la gestion des alertes générées par nos modèles d'intelligence artificielle et l'administration du système.

L'objectif principal est de maximiser l'efficacité opérationnelle des équipes de lutte contre la fraude tout en assurant une parfaite compréhension des scores prédictifs fournis par la technologie de Machine Learning.

## 1.2 Public cible

Ce document s'adresse à trois profils d'utilisateurs distincts, chacun disposant de niveaux d'accès et de responsabilités spécifiques au sein de la plateforme :

- **Les Analystes Fraude (Niveau 1 et 2)** : Opérateurs chargés du traitement quotidien de la file d'attente des alertes, de l'investigation des dossiers suspects, ainsi que de la validation ou du rejet des suspicions de fraude.
- **Les Superviseurs et Responsables d'Équipe** : Utilisateurs ayant la charge de piloter l'activité globale, de suivre les indicateurs de performance (KPIs), d'exporter les données à des fins réglementaires et d'arbitrer les cas d'escalade complexes.
- **Les Administrateurs Fonctionnels** : Profils techniques et métiers responsables de la gestion des habilitations utilisateurs et de la maintenance fonctionnelle de l'écosystème.

## 1.3 Périmètre fonctionnel couvert

Le présent guide couvre l'intégralité du cycle de vie du traitement de la fraude au sein de l'application, depuis l'arrivée d'un flux de transactions jusqu'à l'archivage de la décision finale.

Le périmètre inclut :

- L'authentification sécurisée des utilisateurs.
- Le suivi en temps réel des transactions bancaires injectées dans le système.
- L'analyse décisionnelle assistée par l'intelligence artificielle (interprétation des scores d'inférence et de l'explicabilité des modèles).
- Le workflow collaboratif de gestion, d'affectation et de traitement des alertes.
- L'exploitation des modules d'analyse statistique et de prévision des volumes de fraude.
- Les outils de traçabilité, de gestion des logs et d'audit du système.

## 1.4 Organisation du guide

Pour faciliter la navigation, ce manuel est structuré de manière progressive, suivant le parcours logique d'un utilisateur découvrant la solution.

---

# Chapitre 2 : Accès à la plateforme

## 2.1 Prérequis d'accès

### 2.1.1 Navigateurs internet supportés

L'interface utilisateur, développée avec Angular, est optimisée afin d'assurer une navigation fluide ainsi qu'un rafraîchissement des données en temps réel. Pour garantir une expérience optimale, il est recommandé d'utiliser les versions récentes des navigateurs suivants :

- **Google Chrome version 110 ou supérieure** – Recommandé
- **Mozilla Firefox version 115 ou supérieure**
- **Microsoft Edge version 110 ou supérieure**
- **Apple Safari version 16 ou supérieure**

> **⚠️ Effet sur le système**  
> Le navigateur Internet Explorer n'est pas pris en charge. L'activation de JavaScript ainsi que l'acceptation des cookies de session sont indispensables au bon fonctionnement de l'application.

### 2.1.2 Configuration réseau et accès VPN requis

Pour des raisons de sécurité bancaire, la plateforme n'est pas accessible directement depuis le réseau Internet public.

- **Accès interne** : être connecté au réseau filaire ou au Wi-Fi sécurisé de l'entreprise.
- **Accès à distance (télétravail)** : l'activation préalable du VPN d'entreprise est obligatoire afin d'accéder aux serveurs d'authentification et de production.

## 2.2 URL d'accès aux services

Selon votre rôle et vos besoins, plusieurs interfaces sont mises à votre disposition :

| Service / Interface | URL d'accès (Local / Dev) | Description |
|---|---|---|
| Portail Fraude Principal | http://localhost:4200 | Interface Angular destinée aux analystes et superviseurs. |
| Console Keycloak | http://localhost:8180 | Gestion des accès, des rôles et des mots de passe (réservé aux administrateurs). |
| Console MinIO | http://localhost:9001 | Stockage des fichiers, des modèles de Machine Learning et des artefacts système (réservé aux administrateurs). |

> **Accès local aux services de la plateforme**

## 2.3 Authentification et connexion

### 2.3.1 Procédure de première connexion

La sécurité des accès est centralisée par notre composant Keycloak. Lors de la création de votre compte par un administrateur, votre adresse e-mail est initialement enregistrée avec le statut "Non vérifié" dans le système de sécurité.

1. Rendez-vous sur l'URL du Portail Fraude Principal.
2. Saisissez vos identifiants professionnels provisoires fournis par l'administrateur (adresse e-mail de l'entreprise et mot de passe temporaire).
3. S'il s'agit de votre première connexion, le système vous invitera immédiatement à modifier votre mot de passe temporaire pour définir un code secret personnalisé conforme à la politique de sécurité interne.

### 2.3.2 Validation du compte et vérification de l'e-mail par code OTP

Dès que le nouveau mot de passe est enregistré, Keycloak détecte que le statut de l'adresse e-mail est toujours "Non vérifié" et déclenche automatiquement le protocole de sécurisation par mot de passe à usage unique (OTP) :

1. Un code de vérification OTP est instantanément généré par le système et envoyé sur votre boîte de messagerie professionnelle.
2. Une mire de validation s'affiche sur le Portail Fraude, vous invitant à saisir ce code.
3. Renseignez le code reçu et cliquez sur "Valider".

> **⚠️ Effet sur le système**  
> Si le code OTP saisi est valide, Keycloak bascule automatiquement le statut de votre adresse e-mail en "Vérifié". Cette action finalise définitivement l'activation de votre compte et vous redirige vers le tableau de bord principal de l'application. Les connexions futures ne nécessiteront plus cette étape de vérification d'e-mail, sauf modification de vos paramètres par l'administrateur.

## 2.4 Gestion des sessions utilisateur

La sécurité et la persistance de votre session sur le Portail Fraude sont entièrement gérées au niveau du serveur de l'entreprise.

- **Durée de validité** : Il n'existe pas de durée fixe universelle. La validité maximale de votre session est intégralement paramétrée par l'administrateur système via la console Keycloak, conformément aux exigences de la politique de sécurité de l'établissement.
- **Expiration pour inactivité** : Si aucune action (clic, saisie, changement d'onglet) n'est détectée pendant le délai d'inactivité défini par l'administrateur, la session locale est automatiquement coupée.
- **Verrouillage automatique** : Dès l'expiration du délai, l'interface Angular détruit les jetons d'accès locaux et reverrouille l'application. Vous devrez introduire à nouveau vos identifiants pour récupérer votre espace de travail.

## 2.5 Déconnexion

Pour quitter l'application de manière sécurisée et éviter qu'un tiers n'accède à votre session (notamment lors d'une absence de votre poste de travail) :

1. Cliquez sur l'icone de déconnexion en bas à gauche.
2. Le système invalide immédiatement vos jetons de sécurité sur le serveur local et vous redirige vers la page d'accueil d'authentification.

---

# Chapitre 3 : Vue d'ensemble de l'interface principale

## 3.1 Présentation générale du tableau de bord

Après votre authentification réussie, vous accédez au tableau de bord principal, point d'entrée de votre utilisation quotidienne de la plateforme. Ce tableau de bord est personnalisé en fonction de votre profil (analyste, superviseur ou administrateur) et affiche les informations prioritaires liées à vos responsabilités.

Le tableau de bord principal comporte les zones suivantes :

- **Zone d'en-tête** : affiche votre nom d'utilisateur, le statut de connectivité et les notifications urgentes.
- **Zone de navigation latérale** : menu vertical permettant l'accès aux différents modules (Alertes, Transactions, Rapports, Administration).
- **Zone centrale** : espace de travail affichant les contenus spécifiques au module sélectionné.
- **Zone de pied de page** : informations de session, version de l'application et liens vers le support.

## 3.2 Navigation principale

La navigation au sein de la plateforme s'effectue via le menu latéral gauche, structuré selon les modules fonctionnels :

| Module | Description | Accessible à |
|---|---|---|
| **Alertes** | Gestion, triage et investigation des alertes de fraude | Analystes, Superviseurs |
| **Transactions** | Recherche, filtrage et analyse détaillée des transactions | Analystes, Superviseurs |
| **Modèles ML** | Visualisation des scores, explicabilité et comparaison de modèles | Analystes (lecture), Superviseurs |
| **Rapports** | Génération et export de rapports d'activité et statistiques | Superviseurs, Administrateurs |
| **Administration** | Gestion des utilisateurs, configuration système et audit | Administrateurs |

Chaque module dispose de sous-menus permettant une navigation granulaire au sein de ses fonctionnalités.

## 3.3 Indicateurs clés de performance (KPIs)

Le tableau de bord affiche des indicateurs synthétiques mis à jour en temps réel, permettant un suivi rapide de l'activité globale :

- **Nombre d'alertes en attente** : transactions en attente de validation par un analyste.
- **Taux de fraude détectée** : ratio entre transactions confirmées frauduleuses et transactions totales traitées.
- **Temps moyen de traitement** : délai moyen entre la génération d'une alerte et sa clôture.
- **Efficacité des modèles** : métrique composite intégrant précision, rappel et F-score du meilleur modèle actif.

Ces KPIs sont accessibles en un coup d'œil et peuvent être cliqués pour afficher des détails ou des graphes supplémentaires.

## 3.4 Menu utilisateur et profil

En haut à droite de l'interface, un menu déroulant permet d'accéder à :

- **Mon profil** : consultation et modification des informations personnelles (nom, email, photo de profil).
- **Préférences** : réglage des paramètres d'affichage (langue, thème, notifications).
- **Historique de connexion** : liste de vos dernières connexions avec horodatage et adresse IP.
- **Déconnexion** : fermeture sécurisée de votre session.

---

# Chapitre 4 : Gestion des alertes de fraude

## 4.1 Réception et affichage des alertes

Les alertes sont générées en continu par les modèles de machine learning qui analysent les transactions entrantes. Chaque alerte reçoit un identifiant unique et une date/heure de création.

**Types d'alertes** :

- **Alerte critique** (score de risque > 0.9) : probable fraude confirmée par plusieurs modèles.
- **Alerte haute** (score de risque 0.7 – 0.9) : suspicion forte nécessitant une investigation immédiate.
- **Alerte moyenne** (score de risque 0.4 – 0.7) : comportement suspect méritant validation.
- **Alerte basse** (score de risque < 0.4) : anomalie mineure, archivée automatiquement après 48h d'inactivité.

**Affichage des alertes** :

1. Dans le module **Alertes**, une liste affiche toutes les alertes en attente de traitement.
2. Chaque ligne comporte : l'ID de l'alerte, le type, la date de création, le solde de la transaction, et le score de risque.
3. Un filtre par statut (« En attente », « En cours d'investigation », « Résolue ») permet de cibler votre consultation.

## 4.2 Triage et affectation

Lors du triage, vous procédez à un premier examen de l'alerte pour décider de son traitement :

1. **Sélection de l'alerte** : cliquez sur une ligne pour ouvrir les détails complets.
2. **Lecture des informations** : consultez le résumé de la transaction, le score de risque et les facteurs contribuants.
3. **Affectation** :
   - Cliquez sur le bouton **« Affecter à »** pour assigner l'alerte à un collègue analyste.
   - Choisissez le nom dans la liste déroulante et confirmez.
4. **Changement de statut** : l'alerte passe en statut « Affectée » et reçoit une couleur visuelle (orange).

## 4.3 Investigation et décision

Une fois affectée, l'alerte entre en phase d'investigation approfondie :

1. **Consultation des preuves** :
   - Analysez les détails de la transaction (montant, devise, bénéficiaire, lieu).
   - Visualisez les justifications du modèle ML via les graphes SHAP et les attributions Deep Learning (Captum).
   - Consultez l'historique des transactions antérieures du même client.

2. **Décision** :
   - Cliquez sur **« Valider »** pour confirmer la fraude → l'alerte est marquée comme « Fraude confirmée » et communiquée au département compliance.
   - Cliquez sur **« Rejeter »** pour classer l'alerte comme faux positif → archivage de l'alerte, le modèle en reçoit notification pour améliorer ses apprentissages futurs.
   - Cliquez sur **« Escalader »** si le cas est complexe → l'alerte est notifiée au superviseur pour arbitrage.

3. **Commentaire** : vous pouvez ajouter un commentaire libre justifiant votre décision avant de la valider.

## 4.4 Workflow collaboratif et escalade

**Escalade vers un superviseur** :

Certains cas nécessitent une décision supérieure. Votre superviseur reçoit une notification immédiate et peut :

- Réexaminer le dossier complet avec accès aux mêmes données que vous.
- Valider votre décision ou la modifier.
- Demander des informations supplémentaires auprès du client ou du back-office.

**Historique et traçabilité** :

Chaque action sur une alerte (affectation, changement de statut, décision) est enregistrée dans l'historique de l'alerte avec :
- L'identifiant de l'utilisateur ayant effectué l'action.
- L'horodatage précis.
- Un commentaire optionnel expliquant le motif.

---

# Chapitre 5 : Analyse des transactions

## 5.1 Recherche et filtrage de transactions

Le module **Transactions** permet une recherche approfondie de toutes les opérations traitées par la plateforme.

**Critères de recherche** :

| Critère | Type | Exemple |
|---|---|---|
| Identifiant de transaction | Texte | `TRX-2026-045821` |
| Numéro de compte | Texte | `123456789` |
| Montant | Plage | Entre 1000€ et 50000€ |
| Devise | Sélection | EUR, USD, GBP |
| Date | Plage temporelle | Du 01/01/2026 au 31/12/2026 |
| Bénéficiaire | Texte | Nom ou BIC |
| Statut | Sélection | Approuvée, Rejetée, En cours |
| Score de risque | Plage | > 0.7 |

Entrez vos critères et cliquez sur **« Rechercher »**. Les résultats s'affichent dans un tableau récapitulatif.

## 5.2 Détails et historique d'une transaction

Cliquez sur une transaction pour accéder à sa fiche détaillée, contenant :

**Volet général** :
- Identifiant unique et horodatage.
- Données du compte ordonateur et du bénéficiaire.
- Montant, devise et objet de la transaction.
- Statut actuel et score de risque global.

**Volet historique** :
- Toutes les actions antérieures effectuées sur cette transaction (alertes créées, décisions prises).
- Chronologie des modifications de statut.

**Volet scoring ML** :
- Score attribué par chaque modèle actif avec explication (détails au chapitre 6).

## 5.3 Graphe de relations entre transactions

Pour les dossiers complexes impliquant plusieurs transactions, la plateforme offre une visualisation graphique montrant les relations entre opérations :

- **Nœuds** : représentent les comptes, bénéficiaires ou transactions.
- **Arêtes** : connectent les nœuds en fonction de leurs interactions (même client, montant similaire, localisation, etc.).

Cette vue facilite l'identification de patterns frauduleux coordonnés ou de schémas de blanchiment d'argent.

---

# Chapitre 6 : Scoring et interprétabilité des modèles ML

## 6.1 Comprendre les scores de risque

Chaque transaction reçoit un score de risque composite situé entre 0 et 1 :

- **0.0 – 0.3** : très faible risque → transaction probablement légitime.
- **0.3 – 0.6** : risque modéré → comportement légèrement anormal.
- **0.6 – 0.8** : risque élevé → forte suspicion de fraude.
- **0.8 – 1.0** : risque critique → fraude confirmée par les modèles.

Ce score est issu d'un **ensemble de modèles** (Random Forest, XGBoost, AutoEncoder hybride) dont les prédictions individuelles sont combinées via un mécanisme de votation pondéré.

## 6.2 Explicabilité des modèles (SHAP, Deep Learning)

Comprendre **pourquoi** un score a été attribué est crucial pour votre confiance envers la plateforme.

**Technique SHAP et Deep Learning (Captum)** :

La plateforme utilise SHAP (SHapley Additive exPlanations) ainsi que des méthodes de Deep Learning (Integrated Gradients) pour décomposer chaque prédiction :

1. Ouvrez la fiche de la transaction.
2. Dans le volet "Scoring ML", cliquez sur **« Voir l'explicabilité »**.
3. Un graphique en barres affiche la contribution de chaque variable au score final :
   - Les barres rouges représentent les features aggravantes (augmentant le risque).
   - Les barres bleues représentent les features atténuantes (diminuant le risque).
   - La longueur de la barre indique l'ampleur de l'impact.

**Exemple** : pour une transaction classée à haut risque, vous verrez que l'absence de relevé d'adresse du bénéficiaire (rouge) et le montant inhabituellement élevé pour ce client (rouge) sont les principales causes, tandis que la correspondance de devise et de localisation (bleu) réduisent légèrement le risque.

## 6.3 Comparaison multi-modèles

Chaque modèle utilise des approches distinctes. Comparer leurs scores aide à valider ou remettre en question une décision :

| Modèle | Approche | Score | Statut |
|---|---|---|---|
| **Random Forest Supervisé** | Ensemble d'arbres de décision | 0.82 | Fraude probable |
| **XGBoost Gradient Boosting** | Optimisation itérative | 0.78 | Fraude probable |
| **AutoEncoder Hybride** | Détection d'anomalie + comportement | 0.71 | Suspicion élevée |
| **COPOD Statistique** | Détection d'outlier | 0.64 | Suspicion modérée |

Si 3 modèles s'accordent sur un score > 0.7, la probabilité de fraude est très élevée. Un désaccord total mérite une investigation manuelle approfondie.

---

# Chapitre 7 : Rapports et statistiques

## 7.1 Générer un rapport d'activité

Les superviseurs et administrateurs doivent régulièrement produire des rapports d'activité pour :
- Rendre compte à la direction générale.
- Justifier les ressources engagées.
- Démontrer l'efficacité de la détection.

**Procédure de création** :

1. Accédez au module **Rapports** via le menu latéral.
2. Cliquez sur **« Nouveau rapport »**.
3. Remplissez le formulaire :
   - **Période couverte** : date de début et fin (exemple : 01/01/2026 – 31/03/2026).
   - **Destinataire** : sélectionnez le public (Direction, Compliance, Audit interne).
   - **Éléments à inclure** : cochez les sections (résumé exécutif, statistiques détaillées, cas d'étude, recommandations).
4. Cliquez sur **« Générer »**. La plateforme compile automatiquement les données.
5. Téléchargez le rapport au format PDF ou Excel.

## 7.2 Export de données à fins réglementaires

Les autorités réglementaires (CNIL, Banque de France, etc.) exigent des exports périodiques de données de fraude pour audit externe.

**Procédure d'export sécurisé** :

1. Dans le module **Rapports**, sélectionnez **« Export réglementaire »**.
2. Choisissez le format : CSV anonymisé ou Excel chiffré.
3. Définissez les critères de filtrage (période, type de fraude, etc.).
4. L'export est généré et stocké temporairement sur le serveur avec chiffrement AES-256.
5. Un lien de téléchargement est envoyé à votre adresse e-mail professionnelle.
6. L'accès au fichier expire après 24 heures pour des raisons de sécurité.

> **⚠️ Conformité de données**  
> Tous les exports sont automatiquement loggés et audités. L'administrateur en reçoit une notification avec horodatage et votre identifiant.

## 7.3 Tableaux de bord statistiques

Un module **Statistiques** offre une visualisation temps réel des métriques clés :

- **Graphe d'activité quotidienne** : nombre de transactions et d'alertes générées par jour.
- **Distribution des risques** : histogramme des scores (combien de transactions à chaque niveau de risque).
- **Efficacité des modèles** : courbes ROC, matrice de confusion, F-score.
- **Indicateurs de performance** : temps moyen de traitement, taux de faux positifs, sensibilité.

Ces tableaux sont actualisés toutes les heures et peuvent être exportés pour présentation interne.

---

# Chapitre 8 : Administration et gestion des accès

## 8.1 Gestion des utilisateurs et des rôles

L'administration des accès se fait via la **Console Keycloak** (http://localhost:8180). Cette section s'adresse aux administrateurs système uniquement.

**Créer un nouvel utilisateur** :

1. Connectez-vous à la Console Keycloak avec vos identifiants administrateur.
2. Naviguez vers **Users** dans le menu latéral.
3. Cliquez sur **« Add user »**.
4. Remplissez :
   - **Username** : identifiant unique (prénom.nom).
   - **Email** : adresse professionnelle.
   - **First Name / Last Name** : nom complet.
   - **Enabled** : cochez pour activer immédiatement.
5. Cliquez **Create**. L'utilisateur est créé en tant qu'inactif.
6. Accédez à l'onglet **Credentials** et définissez un mot de passe temporaire.
7. Dans l'onglet **Role Mappings**, attribuez les rôles (analyste, superviseur, administrateur).
8. Validez. Un e-mail est envoyé à l'utilisateur avec ses identifiants provisoires.

**Attribution des rôles** :

| Rôle | Permissions |
|---|---|
| **analyste-fraude** | Accès aux alertes et transactions, consultation des scores ML |
| **superviseur** | Accès complet aux alertes, gestion des escalades, génération de rapports |
| **administrateur** | Gestion des utilisateurs, configuration système, audit et monitoring |

## 8.2 Configuration des paramètres système

Dans l'onglet **Administration** de la plateforme (accès administrateur uniquement) :

**Seuils d'alerte** :
- Modifiez les seuils de score déclenchant une alerte (par défaut : 0.75).
- Activez/désactivez certains modèles pour la production.

**Durée des sessions** :
- Durée maximale d'une session (par défaut : 24 heures).
- Délai d'inactivité avant verrouillage (par défaut : 30 minutes).

**Configuration des canaux de notification** :
- E-mail des superviseurs pour les escalades.
- Webhooks internes pour intégration à d'autres systèmes.

## 8.3 Audit et traçabilité des actions

Chaque action utilisateur (connexion, modification de donnée, décision sur alerte) est enregistrée dans le **Registre d'audit**. Accessible via **Administration > Audit** :

| Élément | Détail |
|---|---|
| **Utilisateur** | Identifiant de la personne ayant effectué l'action |
| **Action** | Type d'opération (Login, Create Alert, Modify User) |
| **Ressource** | Identifiant de la transaction/alerte affectée |
| **Horodatage** | Date et heure précises (UTC) |
| **Adresse IP** | Provenance de la requête |
| **Statut** | Succès ou échec |

Vous pouvez filtrer ce registre par date, utilisateur ou type d'action, et l'exporter pour archivage légal.

---

# Chapitre 9 : Troubleshooting et FAQ

## 9.1 Problèmes courants d'accès et de session

**Je ne peux pas me connecter**

- Vérifiez votre connexion réseau et votre accès VPN (le cas échéant).
- Assurez-vous que vous utilisez une adresse e-mail valide associée à votre compte.
- Réinitialisez votre mot de passe en cliquant sur « Mot de passe oublié ? ».
- Si le problème persiste, contactez le support (voir section 9.3).

**Ma session a expiré sans avertissement**

- Vous avez peut-être dépassé la durée d'inactivité autorisée (30 minutes par défaut).
- Reconnectez-vous pour restaurer l'accès. Les données non sauvegardées seront perdues.

**Je vois un message "Accès refusé" à un module**

- Vérifiez votre niveau de rôle (analyste, superviseur, administrateur) avec votre responsable.
- Certains modules (Administration, Rapports) sont réservés à des profils spécifiques.

## 9.2 Problèmes de performance

**L'interface est lente, les données mettent longtemps à charger**

- Vérifiez votre bande passante réseau. Les vidéos/téléchargements lourds ralentissent le chargement des données.
- Rafraîchissez la page (Ctrl + F5) pour effacer le cache navigateur.
- Réduisez la plage temporelle de votre recherche (par exemple : 7 jours au lieu de 1 an).

**Les graphes (SHAP, statistiques) ne s'affichent pas**

- Assurez-vous que JavaScript est activé dans votre navigateur.
- Désactivez les extensions navigateur (ad-blockers, VPN) qui pourraient interférer avec le rendu.
- Essayez un autre navigateur pour isoler le problème.

**Le système affiche des scores "N/A" pour certaines transactions**

- Les modèles ML peuvent avoir échoué à traiter la transaction (données incomplètes, erreur de calcul).
- Cette situation est rare mais normale. Contactez le support si le problème est systématique.

## 9.3 Contacts et support

**Support technique** :

- **E-mail** : support-fraude@banque.fr
- **Téléphone** : +33 (0) 1 XX XX XX XX (disponible 8h – 18h, jours ouvrables)
- **Chat interne** : accessible via l'icone de bulle de dialogue en haut à droite

**Escalade urgente** (système complètement indisponible) :

- Contactez immédiatement le **responsable IT** ou le **responsable compliance** de votre établissement.
- Mentionnez le code incident fourni par le système ou votre horodatage d'erreur exact.

**Documentation supplémentaire** :

- Consulter le fichier `backend/HELP.md` pour les détails techniques.
- Consulter `frontend/README.md` pour la documentation front-end développeurs.
- Visiter le wiki interne de la plateforme (accès après authentification).

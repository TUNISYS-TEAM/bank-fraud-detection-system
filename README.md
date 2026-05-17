# Fraud Detection Platform

## Guide d'utilisation rapide

Ce fichier présente un aperçu général du projet et des commandes de base pour démarrer les composants principaux.

## Guide d'utilisation détaillé

Un guide détaillé séparé est disponible dans `USER_GUIDE.md` pour une documentation plus approfondie des services, des installations et des scenarios.

## Sommaire

1. Introduction
2. Architecture générale
3. Backend Java / Spring Boot
   3.1. Installation et démarrage
   3.2. Principaux modules
   3.3. Configuration
4. Frontend Angular
   4.1. Installation et démarrage
   4.2. Structure du projet
   4.3. Tests
5. Services ML
   5.1. forecast-service
   5.2. ml-hybrid-service
   5.3. ml-onnx-service
6. Conteneurs Docker
   6.1. Composition et services
   6.2. Démarrage
7. Bibliothèque partagée (shared-ml)
8. Bonnes pratiques
9. Ressources utiles

## 1. Introduction

Ce projet est une plateforme de détection de fraude composée de plusieurs briques :
- un backend Java Spring Boot,
- une interface frontend Angular,
- des services de machine learning Python,
- des conteneurs Docker pour l'orchestration.

## 2. Architecture générale

La plateforme contient les composants suivants :
- `backend/` : API et logique métier en Java.
- `frontend/` : application utilisateur en Angular.
- `forecast-service/` : service ML pour la prévision.
- `ml-hybrid-service/` : service ML hybride pour l'inférence.
- `ml-onnx-service/` : service ML ONNX pour constructions de modèles et export.
- `shared-ml/` : bibliothèque partagée pour les modèles et utilitaires ML.
- `docker/` : configuration Docker Compose et scripts d'initialisation.

## 3. Backend Java / Spring Boot

### 3.1. Installation et démarrage

- Utiliser le wrapper Maven inclus : `backend/mvnw` ou `backend/mvnw.cmd`.
- Lancer l'application depuis `backend/` :
  - `./mvnw spring-boot:run` sur macOS/Linux,
  - `mvnw.cmd spring-boot:run` sur Windows.

### 3.2. Principaux modules

- `backend/src/main/java/com/fraud/backend/` : code source de l'application.
- `data/service/frauddetection` : services de détection et d'alerte.
- `resources/` : configuration et fichiers statiques.

### 3.3. Configuration

- Le backend utilise Spring Boot avec la configuration dans `application.yml`.
- Vérifier les paramètres de base de données, d'API et d'environnement.

## 4. Frontend Angular

### 4.1. Installation et démarrage

- Installer les dépendances depuis `frontend/` avec `npm install`.
- Démarrer le serveur de développement : `ng serve`.
- Accéder à l'application à `http://localhost:4200/`.

### 4.2. Structure du projet

- `frontend/src/app/` : composants et services Angular.
- `frontend/src/assets/` : ressources statiques.
- `frontend/src/environments/` : variables d'environnement pour build.

### 4.3. Tests

- Tests unitaires : `ng test`.
- Tests end-to-end : `ng e2e`.

## 5. Services ML

### 5.1. forecast-service

- Service Python orienté prévision.
- Contient `app/` avec API, modèles, prédicteurs et utilitaires.
- Utilise `requirements.txt` pour les dépendances.

### 5.2. ml-hybrid-service

- Service Python hybride avec modèles d'inférence et scripts de formation.
- Contient `app/` et `scripts/` pour entraînement.
- Utilise `requirements.txt`.

### 5.3. ml-onnx-service

- Service Python pour l'export et l'inférence ONNX.
- Structure basée sur `pyproject.toml` et `requirements-ml.txt`.
- Contient `src/`, `scripts/`, et tests.

## 6. Conteneurs Docker

### 6.1. Composition et services

- Le dossier `docker/` contient `docker-compose.yml` pour lancer les services.
- Les services peuvent inclure le backend, la base de données et les services ML.

### 6.2. Démarrage

- Lancer : `docker compose up -d` depuis `docker/`.
- Utiliser `docker compose down` pour arrêter.
- Vérifier les logs avec `docker compose logs -f`.

## 7. Bibliothèque partagée (shared-ml)

- Fournit des utilitaires et composants réutilisables pour les modèles ML.
- Utilisé par les services `forecast-service`, `ml-hybrid-service` et `ml-onnx-service`.

## 8. Bonnes pratiques

- Garder les environnements isolés pour Python (`venv`) et Node.js.
- Valider les changements avec les tests backend et frontend.
- Documenter les nouveaux modules ou services ajoutés.
- Vérifier la compatibilité Docker avant déploiement.

## 9. Ressources utiles

- `backend/HELP.md` pour les guides Spring Boot et Maven.
- `frontend/README.md` pour l'usage Angular.
- Documentation Spring Boot et Angular CLI.
- `docker/init-databases.sh/` pour initialisation de bases de données.

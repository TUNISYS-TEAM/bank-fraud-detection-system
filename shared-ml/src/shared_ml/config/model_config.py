from __future__ import annotations

import json
import logging
from pathlib import Path

from shared_ml.schemas.config_schema import FullModelConfig

logger = logging.getLogger(__name__)

_DEFAULT_PATH = Path(__file__).parent / "model_config.json"


class ModelConfig:
    """
    Lit le fichier model_config.json et expose des méthodes de construction build_*() typées.

    Tous les paramètres proviennent du JSON — aucune valeur par défaut n'est codée en dur ici.
    Si une clé est manquante dans le JSON, une erreur claire est levée pour que 
    le développeur UI sache exactement quel champ doit être ajouté.
    """

    def __init__(self, path: str | Path | None = None):
        self._path = Path(path) if path else _DEFAULT_PATH
        self._cfg = self._load()
        logger.info("ModelConfig loaded from %s", self._path)

    def _load(self) -> FullModelConfig:
        if not self._path.exists():
            raise FileNotFoundError(
                f"model_config.json not found at {self._path}. "
                "Make sure Spring Boot has written the config file."
            )
        try:
            with open(self._path, encoding="utf-8") as f:
                data = json.load(f)
            return FullModelConfig.model_validate(data)
        except Exception as e:
            logger.error("Configuration validation failed: %s", e)
            raise

    def reload(self) -> None:
        """Relit à partir du disque — appeler ceci si Spring Boot a mis à jour le fichier."""
        self._cfg = self._load()
        logger.info("ModelConfig reloaded from %s", self._path)

    def save(self, path: str | Path | None = None) -> None:
        """Écrit la configuration actuelle sur le disque (par exemple après les mises à jour des seuils)."""
        out = Path(path) if path else self._path
        out.parent.mkdir(parents=True, exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(self._cfg.model_dump(by_alias=True), f, indent=2)
        logger.info("ModelConfig saved to %s", out)

    def get(self, *keys):
        """Accès sécurisé aux clés imbriquées avec des messages d'erreur clairs."""
        node = self._cfg
        for k in keys:
            if hasattr(node, k):
                node = getattr(node, k)
            elif isinstance(node, dict) and k in node:
                node = node[k]
            else:
                raise KeyError(
                    f"ModelConfig: key '{k}' not found. "
                    f"Add it to model_config.json."
                )
        return node

    def is_enabled(self, model_name: str) -> bool:
        model_cfg = getattr(self._cfg, model_name, None)
        return bool(getattr(model_cfg, "enabled", True))

    def build_lightgbm(self):
        from ml_pipeline.models.supervised.lightgbm_model import LightGBMFraudModel

        c = self._cfg.lightgbm
        g = self._cfg.global_config
        return LightGBMFraudModel(
            n_estimators=c.n_estimators,
            learning_rate=c.learning_rate,
            max_depth=c.max_depth,
            num_leaves=c.num_leaves,
            min_child_samples=c.min_child_samples,
            subsample=c.subsample,
            colsample_bytree=c.colsample_bytree,
            reg_alpha=c.reg_alpha,
            reg_lambda=c.reg_lambda,
            early_stopping_rounds=c.early_stopping_rounds,
            calibrate=c.calibrate,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    def build_xgboost(self):
        from ml_pipeline.models.supervised.tree_models import XGBoostFraudModel

        c = self._cfg.xgboost
        g = self._cfg.global_config
        return XGBoostFraudModel(
            n_estimators=c.n_estimators,
            max_depth=c.max_depth,
            learning_rate=c.learning_rate,
            subsample=c.subsample,
            colsample_bytree=c.colsample_bytree,
            early_stopping_rounds=c.early_stopping_rounds,
            calibrate=c.calibrate,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    def build_random_forest(self):
        from ml_pipeline.models.supervised.tree_models import RandomForestFraudModel

        c = self._cfg.random_forest
        g = self._cfg.global_config
        return RandomForestFraudModel(
            n_estimators=c.n_estimators,
            max_depth=c.max_depth,
            min_samples_leaf=c.min_samples_leaf,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    def build_catboost(self, cat_features=None):
        from ml_pipeline.models.supervised.tree_models import CatBoostFraudModel

        c = self._cfg.catboost
        g = self._cfg.global_config
        return CatBoostFraudModel(
            iterations=c.iterations,
            depth=c.depth,
            learning_rate=c.learning_rate,
            l2_leaf_reg=c.l2_leaf_reg,
            early_stopping_rounds=c.early_stopping_rounds,
            cat_features=cat_features,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    def build_autoencoder(self):
        from ml_pipeline.models.unsupervised.anomaly_models import AnomalyAutoencoder

        c = self._cfg.autoencoder
        g = self._cfg.global_config
        return AnomalyAutoencoder(
            encoding_dim=c.encoding_dim,
            epochs=c.epochs,
            batch_size=c.batch_size,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    def build_ecod(self):
        from ml_pipeline.models.unsupervised.anomaly_models import ECODFraudModel

        c = self._cfg.ecod
        g = self._cfg.global_config
        return ECODFraudModel(
            contamination=c.contamination,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    def build_copod(self):
        from ml_pipeline.models.unsupervised.anomaly_models import COPODFraudModel

        c = self._cfg.copod
        g = self._cfg.global_config
        return COPODFraudModel(
            contamination=c.contamination,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    def build_ensemble(self, fitted_models: dict):
        from ml_pipeline.models.ensemble.ensemble import (
            ANDEnsemble,
            ORBoostEnsemble,
            WeightedEnsemble,
        )

        c = self._cfg.ensemble
        strategy = c.strategy
        names = c.models
        models = [fitted_models[n] for n in names if n in fitted_models]

        if not models:
            raise ValueError("build_ensemble: no matching fitted models found")

        if strategy == "weighted":
            return WeightedEnsemble(
                models=models,
                beta=c.beta,
                n_weight_restarts=c.n_weight_restarts,
            )
        if strategy == "and":
            return ANDEnsemble(models=models, beta=c.beta)
        if strategy == "or_boost":
            return ORBoostEnsemble(
                models=models,
                high_conf_threshold=c.and_high_conf_threshold,
                beta=c.beta,
            )
        raise ValueError(f"Unknown ensemble strategy: '{strategy}'.")

    def build_all(self) -> dict:
        """Construit et retourne tous les modèles activés sous forme de dictionnaire."""
        builders = {
            "lightgbm": self.build_lightgbm,
            "xgboost": self.build_xgboost,
            "random_forest": self.build_random_forest,
            "catboost": self.build_catboost,
            "autoencoder": self.build_autoencoder,
            "ecod": self.build_ecod,
            "copod": self.build_copod,
        }
        return {
            name: builder()
            for name, builder in builders.items()
            if self.is_enabled(name)
        }

    def __repr__(self) -> str:
        data = self._cfg.model_dump()
        enabled = [
            k for k, v in data.items()
            if isinstance(v, dict) and v.get("enabled", False)
        ]
        return f"ModelConfig(path={self._path}, enabled={enabled})"
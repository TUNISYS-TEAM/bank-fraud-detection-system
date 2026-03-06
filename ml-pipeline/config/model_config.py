"""
model_config.py
===============
Loads model_config.json and instantiates all models from it.

Java / Spring Boot writes to config/model_config.json.
Python reads it here — no hardcoded params anywhere in training code.

Usage
-----
    from config.model_config import ModelConfig

    cfg = ModelConfig()                          # loads default path
    cfg = ModelConfig("path/to/model_config.json")

    lgb_model = cfg.build_lightgbm()
    all_models = cfg.build_all()                 # returns only enabled models
    cfg.save("path/to/updated_config.json")      # write back after UI changes
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

_DEFAULT_PATH = Path(__file__).parent / "model_config.json"


class ModelConfig:
    """
    Reads model_config.json and exposes typed build_*() methods.

    All parameters come from the JSON — no defaults are hardcoded here.
    If a key is missing from the JSON, a clear KeyError is raised so
    the UI developer knows exactly which field needs to be added.
    """

    def __init__(self, path: str | Path | None = None):
        self._path = Path(path) if path else _DEFAULT_PATH
        self._cfg  = self._load()
        logger.info("ModelConfig loaded from %s", self._path)

    # ------------------------------------------------------------------
    def _load(self) -> dict:
        if not self._path.exists():
            raise FileNotFoundError(
                f"model_config.json not found at {self._path}. "
                "Make sure Spring Boot has written the config file."
            )
        with open(self._path, encoding="utf-8") as f:
            return json.load(f)

    def reload(self):
        """Re-read from disk — call this if Spring Boot updated the file."""
        self._cfg = self._load()
        logger.info("ModelConfig reloaded from %s", self._path)

    def save(self, path: str | Path | None = None):
        """Write current config back to disk (e.g. after threshold updates)."""
        out = Path(path) if path else self._path
        out.parent.mkdir(parents=True, exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(self._cfg, f, indent=2)
        logger.info("ModelConfig saved → %s", out)

    def get(self, *keys):
        """Safe nested key access with clear error messages."""
        node = self._cfg
        for k in keys:
            if k not in node:
                raise KeyError(
                    f"ModelConfig: key '{k}' not found under {keys[:-1]}. "
                    f"Add it to model_config.json."
                )
            node = node[k]
        return node

    def is_enabled(self, model_name: str) -> bool:
        return bool(self._cfg.get(model_name, {}).get("enabled", True))

    # ------------------------------------------------------------------
    # Build methods — one per model type
    # ------------------------------------------------------------------

    def build_lightgbm(self):
        from models.supervised.lightgbm_model import LightGBMFraudModel
        c = self._cfg["lightgbm"]
        g = self._cfg["global"]
        return LightGBMFraudModel(
            n_estimators           = c["n_estimators"],
            learning_rate          = c["learning_rate"],
            max_depth              = c["max_depth"],
            num_leaves             = c["num_leaves"],
            min_child_samples      = c["min_child_samples"],
            subsample              = c["subsample"],
            colsample_bytree       = c["colsample_bytree"],
            reg_alpha              = c["reg_alpha"],
            reg_lambda             = c["reg_lambda"],
            early_stopping_rounds  = c["early_stopping_rounds"],
            calibrate              = c["calibrate"],
            threshold_metric       = g["threshold_metric"],
            beta                   = g["threshold_beta"],
        )

    def build_xgboost(self):
        from models.supervised.tree_models import XGBoostFraudModel
        c = self._cfg["xgboost"]
        g = self._cfg["global"]
        return XGBoostFraudModel(
            n_estimators           = c["n_estimators"],
            max_depth              = c["max_depth"],
            learning_rate          = c["learning_rate"],
            subsample              = c["subsample"],
            colsample_bytree       = c["colsample_bytree"],
            early_stopping_rounds  = c["early_stopping_rounds"],
            calibrate              = c["calibrate"],
            threshold_metric       = g["threshold_metric"],
            beta                   = g["threshold_beta"],
        )

    def build_random_forest(self):
        from models.supervised.tree_models import RandomForestFraudModel
        c = self._cfg["random_forest"]
        g = self._cfg["global"]
        return RandomForestFraudModel(
            n_estimators     = c["n_estimators"],
            max_depth        = c["max_depth"],
            min_samples_leaf = c["min_samples_leaf"],
            threshold_metric = g["threshold_metric"],
            beta             = g["threshold_beta"],
        )

    def build_catboost(self, cat_features=None):
        from models.supervised.tree_models import CatBoostFraudModel
        c = self._cfg["catboost"]
        g = self._cfg["global"]
        return CatBoostFraudModel(
            iterations            = c["iterations"],
            depth                 = c["depth"],
            learning_rate         = c["learning_rate"],
            l2_leaf_reg           = c["l2_leaf_reg"],
            early_stopping_rounds = c["early_stopping_rounds"],
            cat_features          = cat_features,
            threshold_metric      = g["threshold_metric"],
            beta                  = g["threshold_beta"],
        )

    def build_autoencoder(self):
        from models.unsupervised.anomaly_models import AnomalyAutoencoder
        c = self._cfg["autoencoder"]
        g = self._cfg["global"]
        return AnomalyAutoencoder(
            encoding_dim     = c["encoding_dim"],
            epochs           = c["epochs"],
            batch_size       = c["batch_size"],
            threshold_metric = g["threshold_metric"],
            beta             = g["threshold_beta"],
        )

    def build_ecod(self):
        from models.unsupervised.anomaly_models import ECODFraudModel
        c = self._cfg["ecod"]
        g = self._cfg["global"]
        return ECODFraudModel(
            contamination    = c["contamination"],
            threshold_metric = g["threshold_metric"],
            beta             = g["threshold_beta"],
        )

    def build_copod(self):
        from models.unsupervised.anomaly_models import COPODFraudModel
        c = self._cfg["copod"]
        g = self._cfg["global"]
        return COPODFraudModel(
            contamination    = c["contamination"],
            threshold_metric = g["threshold_metric"],
            beta             = g["threshold_beta"],
        )

    def build_ensemble(self, fitted_models: dict):
        """
        Parameters
        ----------
        fitted_models : dict mapping name → fitted FraudBaseModel
                        e.g. {"lightgbm": lgb_m, "xgboost": xgb_m, "ecod": ecod_m}
        """
        from models.ensemble.ensemble import WeightedEnsemble, ANDEnsemble, ORBoostEnsemble
        c        = self._cfg["ensemble"]
        strategy = c["strategy"]
        names    = c["models"]
        models   = [fitted_models[n] for n in names if n in fitted_models]

        if not models:
            raise ValueError("build_ensemble: no matching fitted models found")

        if strategy == "weighted":
            return WeightedEnsemble(
                models             = models,
                beta               = c["beta"],
                n_weight_restarts  = c["n_weight_restarts"],
            )
        elif strategy == "and":
            return ANDEnsemble(models=models, beta=c["beta"])
        elif strategy == "or_boost":
            return ORBoostEnsemble(
                models                = models,
                high_conf_threshold   = c["and_high_conf_threshold"],
                beta                  = c["beta"],
            )
        else:
            raise ValueError(f"Unknown ensemble strategy: '{strategy}'. "
                             f"Choose from: weighted, and, or_boost")

    # ------------------------------------------------------------------
    def build_all(self) -> dict:
        """
        Build and return all enabled models as a dict.
        Called by train_models.py — no params anywhere else.
        """
        builders = {
            "lightgbm":       self.build_lightgbm,
            "xgboost":        self.build_xgboost,
            "random_forest":  self.build_random_forest,
            "catboost":       self.build_catboost,
            "neural_network": self.build_neural_network,
            "autoencoder":    self.build_autoencoder,
            "ecod":           self.build_ecod,
            "copod":          self.build_copod,
        }
        return {
            name: builder()
            for name, builder in builders.items()
            if self.is_enabled(name)
        }

    # ------------------------------------------------------------------
    def __repr__(self):
        enabled = [k for k in self._cfg if self.is_enabled(k)]
        return f"ModelConfig(path={self._path}, enabled={enabled})"
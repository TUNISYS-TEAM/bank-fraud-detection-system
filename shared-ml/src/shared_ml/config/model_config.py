from __future__ import annotations

import json
import logging
from pathlib import Path

from shared_ml.schemas.config_schema import FullModelConfig

logger = logging.getLogger(__name__)

_DEFAULT_PATH = Path(__file__).parent / "model_config.json"


class ModelConfig:
    """
    Lit le fichier model_config.json et expose les paramètres de configuration typés.

    Tous les paramètres proviennent du JSON — aucune valeur par défaut n'est codée en dur ici.
    Si une clé est manquante dans le JSON, une erreur claire est levée.
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

    def __repr__(self) -> str:
        data = self._cfg.model_dump()
        enabled = [
            k for k, v in data.items()
            if isinstance(v, dict) and v.get("enabled", False)
        ]
        return f"ModelConfig(path={self._path}, enabled={enabled})"
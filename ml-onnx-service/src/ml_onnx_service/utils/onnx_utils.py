from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxmltools
import onnxruntime as ort
from onnxmltools.convert.common.data_types import FloatTensorType as OxmlFloatTensorType
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType as SkFloatTensorType

logger = logging.getLogger(__name__)


def _unwrap_model(model):
    """
    Extrait le vrai modèle exportable à partir d'un wrapper projet.

    Règle importante :
    - on unwrap d'abord le wrapper custom (_model, model_, etc.)
    - on s'arrête si on a déjà un estimateur sklearn LightGBM/XGBoost/RandomForest
    - on ne descend vers booster_ / booster qu'en dernier recours
    """
    current = model

    for attr in (
        "_model",
        "_calibrated",
        "model_",
        "model",
        "estimator_",
        "estimator",
        "clf",
        "classifier",
    ):
        if hasattr(current, attr):
            inner = getattr(current, attr)
            if inner is not None and inner is not current:
                current = inner
                break

    module_name = type(current).__module__
    class_name = type(current).__name__

    if module_name.startswith("lightgbm.sklearn"):
        return current
    if module_name.startswith("xgboost.sklearn"):
        return current
    if module_name.startswith("sklearn.ensemble"):
        return current

    if class_name in {
        "LGBMClassifier",
        "LGBMRegressor",
        "XGBClassifier",
        "XGBRegressor",
        "RandomForestClassifier",
        "RandomForestRegressor",
    }:
        return current

    for attr in ("booster_", "booster"):
        if hasattr(current, attr):
            inner = getattr(current, attr)
            if inner is not None and inner is not current:
                return inner

    return current


def convert_tree_model_to_onnx(
    model: Any,
    model_type: str,
    output_dir: str | Path,
    input_dim: int,
    feature_names: list[str] | None = None,
) -> Path:
    """
    Convertit un modèle arbre en ONNX.
    Supporte :
    - lightgbm via onnxmltools
    - xgboost via onnxmltools
    - random_forest via skl2onnx
    """
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    output_path = output_dir / f"{model_type}_model.onnx"
    inner_model = _unwrap_model(model)

    logger.info("ONNX export inner model type: %s", type(inner_model))

    if model_type == "lightgbm":
        initial_types = [("float_input", OxmlFloatTensorType([None, input_dim]))]
        onnx_model = onnxmltools.convert_lightgbm(
            inner_model,
            initial_types=initial_types,
        )

    elif model_type == "xgboost":
        initial_types = [("float_input", OxmlFloatTensorType([None, input_dim]))]
        onnx_model = onnxmltools.convert_xgboost(
            inner_model,
            initial_types=initial_types,
        )

    elif model_type == "random_forest":
        initial_types = [("float_input", SkFloatTensorType([None, input_dim]))]
        onnx_model = convert_sklearn(
            inner_model,
            initial_types=initial_types,
        )

    else:
        raise ValueError(f"Unsupported tree model_type: {model_type}")

    onnx.save_model(onnx_model, str(output_path))
    logger.info("ONNX model saved → %s", output_path)
    return output_path


def verify_onnx_equivalence(
    original_model: Any,
    onnx_path: str | Path,
    test_data: np.ndarray,
    tolerance: float = 1e-5,
) -> bool:
    """
    Vérifie l'équivalence entre le modèle Python et le modèle ONNX.
    Gère aussi les sorties ONNX de type liste de dictionnaires {classe: proba}.
    """
    X = np.asarray(test_data, dtype=np.float32)
    onnx_path = Path(onnx_path)

    if hasattr(original_model, "predict_proba"):
        py_proba = np.asarray(original_model.predict_proba(X))
        py_scores = (
            py_proba[:, 1]
            if py_proba.ndim == 2 and py_proba.shape[1] >= 2
            else py_proba.reshape(-1)
        )
    else:
        inner_model = _unwrap_model(original_model)
        if hasattr(inner_model, "predict_proba"):
            proba = np.asarray(inner_model.predict_proba(X))
            py_scores = (
                proba[:, 1]
                if proba.ndim == 2 and proba.shape[1] >= 2
                else proba.reshape(-1)
            )
        elif hasattr(inner_model, "predict"):
            py_scores = np.asarray(inner_model.predict(X)).reshape(-1)
        else:
            raise TypeError(
                "Model does not support predict_proba/predict for ONNX verification."
            )

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    outputs = session.run(None, {input_name: X})

    onnx_scores = None

    for out in reversed(outputs):
        if isinstance(out, np.ndarray):
            arr = np.asarray(out)
            if arr.ndim == 2 and arr.shape[1] == 2:
                onnx_scores = arr[:, 1]
                break
            if arr.ndim == 2 and arr.shape[1] == 1:
                onnx_scores = arr.reshape(-1)
                break
            if arr.ndim == 1:
                onnx_scores = arr
                break

        if isinstance(out, list) and len(out) > 0 and isinstance(out[0], dict):
            extracted = []
            for row in out:
                if 1 in row:
                    extracted.append(row[1])
                elif "1" in row:
                    extracted.append(row["1"])
                else:
                    k = sorted(row.keys(), key=lambda x: str(x))[-1]
                    extracted.append(row[k])
            onnx_scores = np.asarray(extracted, dtype=np.float32)
            break

        if isinstance(out, dict):
            extracted = []
            for key in sorted(out.keys(), key=lambda x: str(x)):
                extracted.append(out[key])
            onnx_scores = np.asarray(extracted, dtype=np.float32).reshape(-1)
            break

    if onnx_scores is None:
        last = outputs[-1]

        if isinstance(last, list) and len(last) > 0 and isinstance(last[0], dict):
            extracted = []
            for row in last:
                if 1 in row:
                    extracted.append(row[1])
                elif "1" in row:
                    extracted.append(row["1"])
                else:
                    k = sorted(row.keys(), key=lambda x: str(x))[-1]
                    extracted.append(row[k])
            onnx_scores = np.asarray(extracted, dtype=np.float32)
        else:
            onnx_scores = np.asarray(last, dtype=np.float32).reshape(-1)

    onnx_scores = np.asarray(onnx_scores, dtype=np.float32).reshape(-1)
    py_scores = np.asarray(py_scores, dtype=np.float32).reshape(-1)

    ok = np.allclose(py_scores, onnx_scores, atol=tolerance)

    if ok:
        logger.info("ONNX equivalence check passed")
    else:
        max_diff = float(np.max(np.abs(py_scores - onnx_scores)))
        logger.warning("ONNX equivalence check failed: max_diff=%.8f", max_diff)

    return ok


def save_onnx_metadata(
    onnx_path: str | Path,
    model_type: str,
    feature_names: list[str] | None = None,
    extra_metadata: dict | None = None,
) -> Path:
    """
    Sauvegarde les métadonnées du modèle ONNX à côté du fichier .onnx.
    Signature alignée avec onnx_exporter.py.
    """
    onnx_path = Path(onnx_path)
    onnx_path.parent.mkdir(parents=True, exist_ok=True)

    meta_path = onnx_path.with_suffix(".meta.json")

    payload = {
        "model_type": model_type,
        "onnx_file": onnx_path.name,
        "feature_names": feature_names or [],
    }

    if extra_metadata:
        payload.update(extra_metadata)

    meta_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    logger.info("ONNX metadata saved → %s", meta_path)
    return meta_path
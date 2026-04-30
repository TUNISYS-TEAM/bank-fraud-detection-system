"""
Train a Hybrid Ensemble model combining supervised models (RF, XGBoost)
and unsupervised anomaly detection (COPOD), with temporal OOF stacking.
Now supports dynamic feature selection: only selected features are used,
and temporal features are generated only if TX_Time is selected.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from pathlib import Path
from datetime import datetime

import joblib
import numpy as np
import pandas as pd
from pyod.models.copod import COPOD
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    confusion_matrix,
    f1_score,
    precision_recall_curve,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.pipeline import Pipeline
from xgboost import XGBClassifier

sys.path.insert(0, "/app")

from shared_ml.evaluation.data_split import temporal_split
from shared_ml.evaluation.thresholding import (
    find_threshold_by_cost,
    find_threshold_by_fbeta,
)
from shared_ml.preprocessing.behavioral import TemporalFeatureTransformer
from shared_ml.preprocessing.encoders import SafeOneHotEncoder
from shared_ml.preprocessing.encoders.feature_selectors import TreeFeatureSelector
from shared_ml.preprocessing.scalers import SafeScaler
from shared_ml.utils.postal_codes import (
    process_customer_postal_fit,
    process_customer_postal_transform,
    process_merchant_postal_fit,
    process_merchant_postal_transform,
)
from app.models.unsupervised.anomaly_models import RankNormalizer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
    stream=sys.stderr,
)
logger = logging.getLogger(__name__)

EXCLUDED_FEATURE_NAMES = {
    "Account_Id",
    "Device_Id",
    "transaction_id",
    "client_id",
    "device_id",
    "account_id",
}


def make_json_safe(obj):
    if isinstance(obj, (np.integer, np.int64, np.int32)):
        return int(obj)
    if isinstance(obj, (np.floating, np.float64, np.float32)):
        return float(obj)
    if isinstance(obj, np.ndarray):
        return obj.tolist()
    if isinstance(obj, dict):
        return {k: make_json_safe(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [make_json_safe(x) for x in obj]
    return obj


def load_hyperparameters(hyperparam_arg: str) -> dict:
    try:
        return json.loads(hyperparam_arg)
    except json.JSONDecodeError:
        try:
            with open(hyperparam_arg, "r") as f:
                return json.load(f)
        except Exception as e:
            raise ValueError(f"Could not load hyperparameters from '{hyperparam_arg}': {e}") from e


def precision_at_k(y_true: np.ndarray, scores: np.ndarray, k: int) -> float:
    y_true = np.asarray(y_true)
    scores = np.asarray(scores)
    n = len(y_true)
    if n == 0:
        return 0.0
    k = max(1, min(int(k), n))
    top_idx = np.argsort(scores)[::-1][:k]
    return float(np.mean(y_true[top_idx]))


def recall_at_k(y_true: np.ndarray, scores: np.ndarray, k: int) -> float:
    y_true = np.asarray(y_true)
    scores = np.asarray(scores)
    positives = int(np.sum(y_true))
    if positives == 0:
        return 0.0
    n = len(y_true)
    if n == 0:
        return 0.0
    k = max(1, min(int(k), n))
    top_idx = np.argsort(scores)[::-1][:k]
    hits = int(np.sum(y_true[top_idx]))
    return float(hits / positives)


def top_n_detection(y_true: np.ndarray, scores: np.ndarray, n: int) -> dict:
    y_true = np.asarray(y_true)
    scores = np.asarray(scores)
    total = len(y_true)
    if total == 0:
        return {"n": 0, "hits": 0, "precision": 0.0, "recall": 0.0}

    n = max(1, min(int(n), total))
    top_idx = np.argsort(scores)[::-1][:n]
    hits = int(np.sum(y_true[top_idx]))
    positives = int(np.sum(y_true))

    return {
        "n": n,
        "hits": hits,
        "precision": float(hits / n),
        "recall": float(hits / positives) if positives > 0 else 0.0,
    }

def compute_fast_feature_importance_from_scores(
    X_val_proc: np.ndarray,
    scores: np.ndarray,
    feature_names: list[str] | None,
) -> dict:
    from scipy.stats import spearmanr

    X = np.asarray(X_val_proc, dtype=np.float32)
    scores = np.asarray(scores).reshape(-1)

    if X.ndim != 2 or X.shape[0] < 5 or X.shape[1] == 0:
        return {}

    importances = {}
    for j in range(X.shape[1]):
        col = X[:, j]

        if np.unique(col).size < 2 or np.unique(scores).size < 2:
            corr = 0.0
        else:
            corr_val, _ = spearmanr(col, scores)
            corr = abs(float(corr_val)) if not np.isnan(corr_val) else 0.0

        fname = str(feature_names[j]) if feature_names is not None and j < len(feature_names) else f"feature_{j}"
        importances[fname] = corr

    total = float(sum(importances.values()))
    if total > 0:
        importances = {k: float(v / total) for k, v in importances.items()}

    return importances


def identify_categorical_columns(df: pd.DataFrame) -> list[str]:
    expected_cats = ['category', 'Channel', 'client_usual_region', 'client_usual_channel', 'client_usual_category']
    exclude = {'TX_Time', 'Account_Id', 'Device_Id', 'Customer_Region', 'Merchant_Region'}
    categorical_cols = []
    for col in df.columns:
        if col in exclude:
            continue
        if col in expected_cats or df[col].dtype == 'object' or df[col].dtype.name == 'category':
            categorical_cols.append(col)
    return categorical_cols


def create_preprocessing_pipeline(categorical_columns: list[str], has_temporal: bool) -> Pipeline:
    steps = [('onehot', SafeOneHotEncoder(categorical_cols=categorical_columns, include_raw_cats=True))]
    if has_temporal:
        steps.append(('temporal', TemporalFeatureTransformer()))
    steps.append(('selector', TreeFeatureSelector()))
    steps.append(('scaler', SafeScaler(scaler_type='robust')))
    return Pipeline(steps)


def build_rf(random_state: int, n_estimators: int, max_depth: int | None) -> RandomForestClassifier:
    return RandomForestClassifier(
        n_estimators=n_estimators,
        max_depth=max_depth,
        random_state=random_state,
        n_jobs=-1,
        class_weight="balanced",
    )


def build_xgb(
    random_state: int,
    n_estimators: int,
    max_depth: int,
    learning_rate: float,
    scale_pos_weight: float,
) -> XGBClassifier:
    return XGBClassifier(
        n_estimators=n_estimators,
        max_depth=max_depth,
        learning_rate=learning_rate,
        random_state=random_state,
        n_jobs=-1,
        subsample=0.8,
        colsample_bytree=0.8,
        reg_alpha=0.1,
        reg_lambda=1.0,
        scale_pos_weight=scale_pos_weight,
        eval_metric="logloss",
    )


def build_temporal_oof_slices(n_rows: int, n_splits: int) -> list[tuple[np.ndarray, np.ndarray]]:
    if n_splits < 2:
        raise ValueError("n_splits must be >= 2")

    fold_size = n_rows // (n_splits + 1)
    if fold_size < 100:
        raise ValueError("Train split too small for temporal OOF with current n_splits.")

    folds: list[tuple[np.ndarray, np.ndarray]] = []
    for i in range(1, n_splits + 1):
        train_end = fold_size * i
        oof_end = fold_size * (i + 1) if i < n_splits else n_rows
        train_idx = np.arange(0, train_end)
        oof_idx = np.arange(train_end, oof_end)
        if len(train_idx) == 0 or len(oof_idx) == 0:
            continue
        folds.append((train_idx, oof_idx))
    return folds


def main():
    parser = argparse.ArgumentParser(description="Train Hybrid Ensemble (RF+XGB+COPOD)")
    parser.add_argument("--dataset-path", required=True)
    parser.add_argument("--dataset-id", required=True)
    parser.add_argument("--job-id", required=True)
    parser.add_argument("--timestamp", required=True)
    parser.add_argument("--hyperparameters", required=True)
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--random-seed", type=int, default=42)
    args = parser.parse_args()

    started_at = datetime.now()
    overall_start = time.time()

    logger.info("Starting Hybrid Ensemble training for dataset %s", args.dataset_id)

    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    try:
        hp = load_hyperparameters(args.hyperparameters)

        model_name = hp.get("model_name", "HYBRID_ENSEMBLE")
        selected_original_features = hp.get("selected_original_features", [])
        if not selected_original_features:
            raise ValueError("No original features selected for training")

        dataset_path = Path(args.dataset_path)
        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_path}")

        if dataset_path.suffix.lower() == ".parquet":
            df = pd.read_parquet(dataset_path)
        else:
            df = pd.read_csv(dataset_path)

        if not df.columns.is_unique:
            logger.warning("Duplicate column names detected. Keeping first occurrence.")
            df = df.loc[:, ~df.columns.duplicated(keep="first")]

        if "fraud" not in df.columns:
            raise ValueError("Target column 'fraud' not found in dataset")

        if "TX_Time" in df.columns:
            df["TX_Time"] = pd.to_datetime(df["TX_Time"], errors="coerce")
            before = len(df)
            df = df.dropna(subset=["TX_Time"]).reset_index(drop=True)
            if len(df) < before:
                logger.warning("Dropped %d rows with invalid TX_Time", before - len(df))

        # Use only selected features, excluding IDs
        available_features = [
            f for f in selected_original_features
            if f in df.columns and f not in EXCLUDED_FEATURE_NAMES
        ]
        missing = set(selected_original_features) - set(available_features) - EXCLUDED_FEATURE_NAMES
        if missing:
            logger.warning("Features not in dataset: %s", missing)
        if not available_features:
            raise ValueError("None of the selected features are available after excluding IDs")

        # Always include TX_Time for temporal split
        feature_cols_for_split = available_features.copy()
        if "TX_Time" not in feature_cols_for_split and "TX_Time" in df.columns:
            feature_cols_for_split.append("TX_Time")

        X = df[feature_cols_for_split].copy()
        y = df["fraud"].values
        df_processed = X.copy()
        df_processed["fraud"] = y

        # Determine if TX_Time is selected
        has_temporal = "TX_Time" in selected_original_features

        logger.info("Selected original features used for Hybrid Ensemble: %s", available_features)

        if "TX_Time" not in df_processed.columns:
            raise ValueError("TX_Time column missing")

        df_train, df_val, df_test = temporal_split(
            df_processed, time_col="TX_Time", train_ratio=0.7, val_ratio=0.15
        )

        logger.info("Applying per-split preprocessing...")

        # Postal codes only if columns present
        if "Customer_Postal_Code" in df_train.columns:
            customer_region_categories = process_customer_postal_fit(df_train)
            df_train = process_customer_postal_transform(df_train, customer_region_categories)
            df_val = process_customer_postal_transform(df_val, customer_region_categories)
            df_test = process_customer_postal_transform(df_test, customer_region_categories)

        if "Merchant_Postal_Code" in df_train.columns:
            merchant_region_categories = process_merchant_postal_fit(df_train)
            df_train = process_merchant_postal_transform(df_train, merchant_region_categories)
            df_val = process_merchant_postal_transform(df_val, merchant_region_categories)
            df_test = process_merchant_postal_transform(df_test, merchant_region_categories)

        X_train = df_train.drop("fraud", axis=1)
        X_val = df_val.drop("fraud", axis=1)
        X_test = df_test.drop("fraud", axis=1)
        y_train = df_train["fraud"].values
        y_val = df_val["fraud"].values
        y_test = df_test["fraud"].values

        logger.info("Train: %d rows (fraud rate %.4f)", len(X_train), y_train.mean())
        logger.info("Val:   %d rows (fraud rate %.4f)", len(X_val), y_val.mean())
        logger.info("Test:  %d rows (fraud rate %.4f)", len(X_test), y_test.mean())

        # Identify categorical columns among selected features
        categorical_columns = identify_categorical_columns(X_train)
        logger.info("Categorical columns to encode: %s", categorical_columns)

        # Build preprocessing pipeline (temporal only if TX_Time selected)
        preprocessor = create_preprocessing_pipeline(categorical_columns, has_temporal)

        X_train_proc = preprocessor.fit_transform(X_train)
        X_val_proc = preprocessor.transform(X_val)
        X_test_proc = preprocessor.transform(X_test)

        # Store postal categories if they were processed
        if "Customer_Postal_Code" in df_train.columns:
            preprocessor._customer_region_categories_ = list(customer_region_categories)
        if "Merchant_Postal_Code" in df_train.columns:
            preprocessor._merchant_region_categories_ = list(merchant_region_categories)

        if hasattr(X_train_proc, "columns") and "TX_Time" in X_train_proc.columns:
            X_train_proc = X_train_proc.drop("TX_Time", axis=1)
            X_val_proc = X_val_proc.drop("TX_Time", axis=1)
            X_test_proc = X_test_proc.drop("TX_Time", axis=1)

        feature_names = None
        try:
            selector = preprocessor.named_steps.get("selector")
            if selector is not None and hasattr(selector, "get_feature_names_out"):
                feature_names = list(selector.get_feature_names_out())
                logger.info("Selected transformed feature count: %d", len(feature_names))
        except Exception as e:
            logger.warning("Could not extract selected feature names from selector: %s", e)

        if hasattr(X_train_proc, "toarray"):
            X_train_proc = X_train_proc.toarray().astype(np.float32)
            X_val_proc = X_val_proc.toarray().astype(np.float32)
            X_test_proc = X_test_proc.toarray().astype(np.float32)

        X_train_proc = np.asarray(X_train_proc, dtype=np.float32)
        X_val_proc = np.asarray(X_val_proc, dtype=np.float32)
        X_test_proc = np.asarray(X_test_proc, dtype=np.float32)

        logger.info(
            "Data processed: Train=%s, Val=%s, Test=%s (%d features)",
            X_train_proc.shape, X_val_proc.shape, X_test_proc.shape, X_train_proc.shape[1],
        )

        rf_n_estimators = int(hp.get("rf_n_estimators", 100))
        rf_max_depth = hp.get("rf_max_depth", 10)
        xgb_n_estimators = int(hp.get("xgb_n_estimators", 100))
        xgb_max_depth = int(hp.get("xgb_max_depth", 6))
        xgb_lr = float(hp.get("xgb_lr", 0.1))
        threshold_metric = hp.get("threshold_metric", "fbeta")
        beta = float(hp.get("beta", 2.0))
        n_splits = int(hp.get("stacking_folds", 4))

        pos = max(int(np.sum(y_train == 1)), 1)
        neg = max(int(np.sum(y_train == 0)), 1)
        scale_pos_weight = float(neg / pos)

        default_contamination = max(0.001, min(0.02, float(y_train.mean())))
        contamination = float(hp.get("contamination", default_contamination))

        train_start = time.time()

        # ------------------------------------------------------------
        # Step 1: temporal OOF stacking on TRAIN
        # ------------------------------------------------------------
        logger.info("Generating temporal OOF predictions for stacking...")
        folds = build_temporal_oof_slices(len(X_train_proc), n_splits=n_splits)

        rf_oof = np.full(len(X_train_proc), np.nan, dtype=np.float32)
        xgb_oof = np.full(len(X_train_proc), np.nan, dtype=np.float32)
        copod_oof = np.full(len(X_train_proc), np.nan, dtype=np.float32)

        used_oof_mask = np.zeros(len(X_train_proc), dtype=bool)

        for fold_idx, (tr_idx, oof_idx) in enumerate(folds, start=1):
            logger.info(
                "Temporal OOF fold %d/%d | train=%d rows | oof=%d rows",
                fold_idx,
                len(folds),
                len(tr_idx),
                len(oof_idx),
            )

            X_tr, X_oof = X_train_proc[tr_idx], X_train_proc[oof_idx]
            y_tr, y_oof = y_train[tr_idx], y_train[oof_idx]

            rf_fold = build_rf(
                random_state=args.random_seed + fold_idx,
                n_estimators=rf_n_estimators,
                max_depth=rf_max_depth,
            )
            rf_fold.fit(X_tr, y_tr)
            rf_oof[oof_idx] = rf_fold.predict_proba(X_oof)[:, 1]

            xgb_fold = build_xgb(
                random_state=args.random_seed + fold_idx,
                n_estimators=xgb_n_estimators,
                max_depth=xgb_max_depth,
                learning_rate=xgb_lr,
                scale_pos_weight=scale_pos_weight,
            )
            xgb_fold.fit(
                X_tr,
                y_tr,
                eval_set=[(X_oof, y_oof)],
                verbose=False,
            )
            xgb_oof[oof_idx] = xgb_fold.predict_proba(X_oof)[:, 1]

            normal_mask_fold = y_tr == 0
            X_tr_normal = X_tr[normal_mask_fold]
            if len(X_tr_normal) == 0:
                raise ValueError("No normal samples available for COPOD fold training.")

            copod_fold = COPOD(contamination=contamination)
            copod_fold.fit(X_tr_normal)

            copod_train_scores = np.asarray(copod_fold.decision_function(X_tr_normal)).reshape(-1)
            fold_normalizer = RankNormalizer().fit(copod_train_scores)

            copod_oof_raw = np.asarray(copod_fold.decision_function(X_oof)).reshape(-1)
            copod_oof[oof_idx] = fold_normalizer.transform(copod_oof_raw)

            used_oof_mask[oof_idx] = True

        if not np.any(used_oof_mask):
            raise RuntimeError("Temporal OOF stacking produced no usable OOF rows.")

        X_meta_train = np.column_stack([
            rf_oof[used_oof_mask],
            xgb_oof[used_oof_mask],
            copod_oof[used_oof_mask],
        ])
        y_meta_train = y_train[used_oof_mask]

        logger.info(
            "Training meta-learner on temporal OOF predictions (%d rows)...",
            len(y_meta_train),
        )
        meta = LogisticRegression(class_weight="balanced", max_iter=1000)
        meta.fit(X_meta_train, y_meta_train)

        # ------------------------------------------------------------
        # Step 2: refit base models on full TRAIN
        # ------------------------------------------------------------
        logger.info("Refitting base models on full train...")

        rf = build_rf(
            random_state=args.random_seed,
            n_estimators=rf_n_estimators,
            max_depth=rf_max_depth,
        )
        rf.fit(X_train_proc, y_train)

        xgb = build_xgb(
            random_state=args.random_seed,
            n_estimators=xgb_n_estimators,
            max_depth=xgb_max_depth,
            learning_rate=xgb_lr,
            scale_pos_weight=scale_pos_weight,
        )
        xgb.fit(
            X_train_proc,
            y_train,
            eval_set=[(X_val_proc, y_val)],
            verbose=False,
        )

        normal_mask_train = y_train == 0
        X_train_normal = X_train_proc[normal_mask_train]
        copod = COPOD(contamination=contamination)
        copod.fit(X_train_normal)

        copod_train_scores = np.asarray(copod.decision_function(X_train_normal)).reshape(-1)
        copod_normalizer = RankNormalizer().fit(copod_train_scores)
        logger.info("COPOD RankNormalizer fitted on %d normal samples", len(copod_train_scores))

        train_time = time.time() - train_start

        # ------------------------------------------------------------
        # Step 3: validation scores for threshold tuning
        # ------------------------------------------------------------
        rf_val = rf.predict_proba(X_val_proc)[:, 1]
        xgb_val = xgb.predict_proba(X_val_proc)[:, 1]
        copod_val_raw = np.asarray(copod.decision_function(X_val_proc)).reshape(-1)
        copod_val_norm = copod_normalizer.transform(copod_val_raw)

        X_meta_val = np.column_stack([rf_val, xgb_val, copod_val_norm])
        y_val_score_norm = meta.predict_proba(X_meta_val)[:, 1]

        feature_importance = compute_fast_feature_importance_from_scores(
            X_val_proc=X_val_proc,
            scores=y_val_score_norm,
            feature_names=feature_names,
        )
        logger.info("Computed fast correlation-based feature importance for %d features", len(feature_importance))

        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_score_norm)
        f1_scores = 2 * precision_vals[:-1] * recall_vals[:-1] / (precision_vals[:-1] + recall_vals[:-1] + 1e-9)

        if len(thresholds_pr) == 0:
            pr_threshold = 0.5
            best_f1 = 0.0
        else:
            best_idx = int(np.argmax(f1_scores))
            pr_threshold = float(thresholds_pr[best_idx])
            best_f1 = float(f1_scores[best_idx])

        logger.info("PR-based normalized-score threshold (max F1): %.4f (F1=%.4f)", pr_threshold, best_f1)

        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(y_val, y_val_score_norm, beta=beta)
        logger.info("F-beta normalized-score threshold (β=%.1f): %.4f (score=%.4f)", beta, fbeta_threshold, fbeta_score_val)

        cost_threshold, min_cost = find_threshold_by_cost(
            y_val, y_val_score_norm, cost_fp=1.0, cost_fn=10.0
        )
        logger.info("Cost-based normalized-score threshold: %.4f (min_cost=%.0f)", cost_threshold, min_cost)

        user_threshold = hp.get("detection_threshold")
        if user_threshold is not None:
            final_threshold = float(user_threshold)
            logger.info("Using UI normalized-score threshold: %.4f", final_threshold)
        else:
            if threshold_metric == "cost":
                final_threshold = float(cost_threshold)
                logger.info("No UI threshold, using cost-based normalized-score threshold: %.4f", final_threshold)
            elif threshold_metric == "pr":
                final_threshold = float(pr_threshold)
                logger.info("No UI threshold, using PR-based normalized-score threshold: %.4f", final_threshold)
            else:
                final_threshold = float(fbeta_threshold)
                logger.info("No UI threshold, using F-beta normalized-score threshold: %.4f", final_threshold)

        # ------------------------------------------------------------
        # Step 4: test evaluation
        # ------------------------------------------------------------
        rf_test = rf.predict_proba(X_test_proc)[:, 1]
        xgb_test = xgb.predict_proba(X_test_proc)[:, 1]
        copod_test_raw = np.asarray(copod.decision_function(X_test_proc)).reshape(-1)
        copod_test_norm = copod_normalizer.transform(copod_test_raw)

        X_meta_test = np.column_stack([rf_test, xgb_test, copod_test_norm])
        y_test_score_norm = meta.predict_proba(X_meta_test)[:, 1]
        y_test_pred = (y_test_score_norm >= final_threshold).astype(int)

        accuracy = accuracy_score(y_test, y_test_pred)
        precision = precision_score(y_test, y_test_pred, zero_division=0)
        recall = recall_score(y_test, y_test_pred, zero_division=0)
        f1 = f1_score(y_test, y_test_pred, zero_division=0)
        auc_roc = roc_auc_score(y_test, y_test_score_norm)
        pr_auc = average_precision_score(y_test, y_test_score_norm)
        cm = confusion_matrix(y_test, y_test_pred)

        k1 = max(1, int(round(len(y_test) * 0.01)))
        k5 = max(1, int(round(len(y_test) * 0.05)))
        k10 = max(1, int(round(len(y_test) * 0.10)))
        positives = int(np.sum(y_test))
        top_n = max(1, positives)

        p_at_1pct = precision_at_k(y_test, y_test_score_norm, k1)
        r_at_1pct = recall_at_k(y_test, y_test_score_norm, k1)
        p_at_5pct = precision_at_k(y_test, y_test_score_norm, k5)
        r_at_5pct = recall_at_k(y_test, y_test_score_norm, k5)
        p_at_10pct = precision_at_k(y_test, y_test_score_norm, k10)
        r_at_10pct = recall_at_k(y_test, y_test_score_norm, k10)
        top_n_stats = top_n_detection(y_test, y_test_score_norm, top_n)

        logger.info("Test metrics with normalized-score threshold %.4f:", final_threshold)
        logger.info("  Accuracy:      %.4f", accuracy)
        logger.info("  Precision:     %.4f", precision)
        logger.info("  Recall:        %.4f", recall)
        logger.info("  F1:            %.4f", f1)
        logger.info("  AUC-ROC:       %.4f", auc_roc)
        logger.info("  PR-AUC:        %.4f", pr_auc)

        # ------------------------------------------------------------
        # Step 5: save artifacts
        # ------------------------------------------------------------
        timestamp = args.timestamp
        output_models_dir = Path("/tmp/fraud-work/output/models")
        output_pipelines_dir = Path("/tmp/fraud-work/output/pipelines")
        output_models_dir.mkdir(parents=True, exist_ok=True)
        output_pipelines_dir.mkdir(parents=True, exist_ok=True)

        pipeline_path = output_pipelines_dir / f"pipeline_{timestamp}.joblib"
        joblib.dump(preprocessor, pipeline_path)
        logger.info("Preprocessor saved to %s", pipeline_path)

        if hasattr(copod, "decision_scores_"):
            del copod.decision_scores_
        if hasattr(copod, "labels_"):
            del copod.labels_
        if hasattr(copod, "X_train_") and copod.X_train_ is not None:
            original_size = copod.X_train_.nbytes
            copod.X_train_ = copod.X_train_.astype(np.float32)
            logger.info(
                "COPOD X_train_ converted float64->float32: %d MB -> %d MB",
                original_size // (1024 * 1024),
                copod.X_train_.nbytes // (1024 * 1024),
            )

        from app.models.bundles import HybridEnsembleBundle

        bundle = HybridEnsembleBundle(
            rf_model=rf,
            xgb_model=xgb,
            copod_model=copod,
            meta_learner=meta,
            preprocessor=preprocessor,
            feature_names=feature_names if feature_names is not None else [f"feature_{i}" for i in range(X_train_proc.shape[1])],
            threshold=final_threshold,
        )
        bundle.set_copod_normalization(None, None, normalizer=copod_normalizer)

        base_filename = f"trained_model_{timestamp}"
        model_path = output_models_dir / f"{base_filename}.joblib"
        temp_model_path = output_models_dir / f"{base_filename}.tmp"
        try:
            joblib.dump(bundle, temp_model_path, compress=("gzip", 3))
            temp_model_path.rename(model_path)
            logger.info("Model saved to %s (%d bytes)", model_path, model_path.stat().st_size)
        finally:
            if temp_model_path.exists():
                temp_model_path.unlink()

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        result_raw = {
            "status": "COMPLETED",
            "job_id": args.job_id,
            "dataset_id": args.dataset_id,
            "model_name": model_name,
            "model_path": str(model_path),
            "pipeline_path": str(pipeline_path),
            "selected_original_features": available_features,
            "training_mode": {
                "scoring": "hybrid_supervised_plus_unsupervised_temporal_oof_stacking",
                "threshold_selection": "supervised_on_validation_normalized_score_scale",
                "uses_labels_for_scoring": True,
                "uses_labels_for_threshold": True,
                "ui_threshold_used_directly": user_threshold is not None,
            },
            "hyperparameters_used": {
                "rf_n_estimators": rf_n_estimators,
                "rf_max_depth": rf_max_depth,
                "xgb_n_estimators": xgb_n_estimators,
                "xgb_max_depth": xgb_max_depth,
                "xgb_lr": xgb_lr,
                "scale_pos_weight": scale_pos_weight,
                "contamination": contamination,
                "threshold_metric": threshold_metric,
                "beta": beta,
                "stacking_folds": len(folds),
                "copod_norm_q_low": 5.0,
                "copod_norm_q_high": 95.0,
            },
            "metrics": {
                "accuracy": float(accuracy),
                "precision": float(precision),
                "recall": float(recall),
                "f1_score": float(f1),
                "auc_roc": float(auc_roc),
                "pr_auc": float(pr_auc),
                "avg_anomaly_score": float(y_test_score_norm.mean()),
            },
            "ranking_metrics": {
                "precision_at_1pct": float(p_at_1pct),
                "recall_at_1pct": float(r_at_1pct),
                "precision_at_5pct": float(p_at_5pct),
                "recall_at_5pct": float(r_at_5pct),
                "precision_at_10pct": float(p_at_10pct),
                "recall_at_10pct": float(r_at_10pct),
                "top_n_detection": top_n_stats,
            },
            "confusion_matrix": {
                "true_negatives": int(cm[0, 0]),
                "false_positives": int(cm[0, 1]),
                "false_negatives": int(cm[1, 0]),
                "true_positives": int(cm[1, 1]),
            },
            "thresholds": {
                "pr_optimal": float(pr_threshold),
                "pr_f1": float(best_f1),
                "fbeta": float(fbeta_threshold),
                "fbeta_score": float(fbeta_score_val),
                "cost": float(cost_threshold),
                "min_cost": float(min_cost),
                "final": float(final_threshold),
            },
            "training_stats": {
                "train_rows": int(len(X_train)),
                "meta_train_rows": int(np.sum(used_oof_mask)),
                "val_rows": int(len(X_val)),
                "test_rows": int(len(X_test)),
                "fraud_rate_test": float(y_test.mean()),
                "train_time_seconds": float(train_time),
                "input_dim": int(X_train_proc.shape[1]),
                "test_positive_count": int(positives),
            },
            "feature_importance": feature_importance,
            "start_time": started_at.isoformat(),
            "end_time": ended_at.isoformat(),
            "duration_seconds": float(total_duration),
        }

        result = make_json_safe(result_raw)

        sys.stdout = original_stdout
        print(json.dumps(result))

    except Exception as e:
        logger.exception("Hybrid Ensemble Training failed")
        sys.stdout = original_stdout
        error_result = {
            "status": "FAILED",
            "error": str(e),
            "job_id": getattr(args, "job_id", None),
            "dataset_id": getattr(args, "dataset_id", None),
        }
        print(json.dumps(error_result))
        sys.exit(1)


if __name__ == "__main__":
    main()
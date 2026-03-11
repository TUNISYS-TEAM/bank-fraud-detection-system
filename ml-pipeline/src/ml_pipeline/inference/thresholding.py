from __future__ import annotations

from typing import Iterable

import numpy as np
from sklearn.metrics import fbeta_score


def _validate_binary_inputs(y_true: np.ndarray, y_score: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """
    Validate binary labels and probability scores for threshold search.
    """
    y_true = np.asarray(y_true).reshape(-1)
    y_score = np.asarray(y_score).reshape(-1)

    if y_true.shape[0] != y_score.shape[0]:
        raise ValueError("y_true and y_score must have the same length.")

    if y_true.size == 0:
        raise ValueError("y_true and y_score must not be empty.")

    unique_labels = set(np.unique(y_true))
    if not unique_labels.issubset({0, 1}):
        raise ValueError("y_true must contain only binary labels {0, 1}.")

    if np.any((y_score < 0) | (y_score > 1)):
        raise ValueError("y_score must contain probabilities in the range [0, 1].")

    return y_true.astype(int), y_score.astype(float)


def _candidate_thresholds(y_score: np.ndarray, n_steps: int = 300) -> np.ndarray:
    """
    Build candidate thresholds for search.

    Uses score-derived thresholds when possible, with a bounded fallback grid.
    """
    unique_scores = np.unique(y_score)

    if unique_scores.size <= n_steps:
        thresholds = unique_scores
    else:
        thresholds = np.linspace(0.0, 1.0, n_steps)

    thresholds = np.clip(thresholds, 0.0, 1.0)
    thresholds = np.unique(thresholds)

    if thresholds[0] != 0.0:
        thresholds = np.insert(thresholds, 0, 0.0)
    if thresholds[-1] != 1.0:
        thresholds = np.append(thresholds, 1.0)

    return thresholds


def find_threshold_by_cost(
    y_true: np.ndarray,
    y_score: np.ndarray,
    cost_fp: float = 1.0,
    cost_fn: float = 5.0,
    n_steps: int = 300,
) -> tuple[float, float]:
    """
    Find the threshold that minimizes the total business cost.

    Cost = FP * cost_fp + FN * cost_fn
    """
    if cost_fp < 0 or cost_fn < 0:
        raise ValueError("cost_fp and cost_fn must be non-negative.")

    y_true, y_score = _validate_binary_inputs(y_true, y_score)
    thresholds = _candidate_thresholds(y_score, n_steps=n_steps)

    best_threshold = 0.5
    min_cost = float("inf")

    for t in thresholds:
        y_pred = (y_score >= t).astype(int)
        fp = int(((y_pred == 1) & (y_true == 0)).sum())
        fn = int(((y_pred == 0) & (y_true == 1)).sum())
        cost = fp * cost_fp + fn * cost_fn

        if cost < min_cost:
            min_cost = cost
            best_threshold = float(t)

    return best_threshold, float(min_cost)


def find_threshold_by_fbeta(
    y_true: np.ndarray,
    y_score: np.ndarray,
    beta: float = 2.0,
    n_steps: int = 300,
) -> tuple[float, float]:
    """
    Find the threshold that maximizes F-beta score.
    """
    if beta <= 0:
        raise ValueError("beta must be strictly positive.")

    y_true, y_score = _validate_binary_inputs(y_true, y_score)
    thresholds = _candidate_thresholds(y_score, n_steps=n_steps)

    best_threshold = 0.5
    best_score = -1.0

    for t in thresholds:
        y_pred = (y_score >= t).astype(int)
        score = fbeta_score(y_true, y_pred, beta=beta, zero_division=0)

        if score > best_score:
            best_score = float(score)
            best_threshold = float(t)

    return best_threshold, best_score


def find_threshold_for_recall(
    y_true: np.ndarray,
    y_score: np.ndarray,
    target_recall: float,
    max_fpr: float | None = None,
    n_steps: int = 300,
) -> float:
    """
    Find the highest threshold that still achieves the target recall.
    Optionally enforce a maximum false positive rate.
    """
    if not (0 <= target_recall <= 1):
        raise ValueError("target_recall must be in the range [0, 1].")

    if max_fpr is not None and not (0 <= max_fpr <= 1):
        raise ValueError("max_fpr must be in the range [0, 1].")

    y_true, y_score = _validate_binary_inputs(y_true, y_score)
    thresholds = _candidate_thresholds(y_score, n_steps=n_steps)

    positives = int((y_true == 1).sum())
    negatives = int((y_true == 0).sum())

    valid_thresholds: list[float] = []

    for t in thresholds:
        y_pred = (y_score >= t).astype(int)

        tp = int(((y_pred == 1) & (y_true == 1)).sum())
        fp = int(((y_pred == 1) & (y_true == 0)).sum())

        recall = tp / positives if positives > 0 else 0.0
        fpr = fp / negatives if negatives > 0 else 0.0

        if recall >= target_recall:
            if max_fpr is None or fpr <= max_fpr:
                valid_thresholds.append(float(t))

    if not valid_thresholds:
        raise ValueError(
            "No threshold satisfies the requested recall"
            + (" and FPR constraint." if max_fpr is not None else ".")
        )

    return max(valid_thresholds)


def apply_threshold(y_score: float, threshold: float) -> bool:
    """
    Apply a threshold to one fraud probability score.
    """
    if not (0 <= y_score <= 1):
        raise ValueError("y_score must be in the range [0, 1].")
    if not (0 <= threshold <= 1):
        raise ValueError("threshold must be in the range [0, 1].")

    return bool(y_score >= threshold)


def adjust_threshold_with_business_rules(
    base_threshold: float,
    transaction_amount: float,
    amount_threshold: float = 1000.0,
    recall_boost: float = 0.10,
    min_threshold: float = 0.05,
) -> float:
    """
    Lower the threshold for high-value transactions to increase sensitivity.

    This is a business rule layer applied on top of the base threshold.
    """
    if not (0 <= base_threshold <= 1):
        raise ValueError("base_threshold must be in the range [0, 1].")

    if transaction_amount < 0:
        raise ValueError("transaction_amount must be non-negative.")

    if amount_threshold < 0:
        raise ValueError("amount_threshold must be non-negative.")

    if not (0 <= recall_boost < 1):
        raise ValueError("recall_boost must be in the range [0, 1).")

    if not (0 <= min_threshold <= 1):
        raise ValueError("min_threshold must be in the range [0, 1].")

    adjusted = base_threshold

    if transaction_amount > amount_threshold:
        adjusted = base_threshold * (1 - recall_boost)

    return float(np.clip(adjusted, min_threshold, 1.0))
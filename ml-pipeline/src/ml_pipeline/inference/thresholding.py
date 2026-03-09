"""
Threshold adjustment logic based on business costs.
Provides functions to find optimal thresholds given a cost matrix,
or to adjust a threshold to achieve a target recall/precision.
"""

from typing import Tuple, Optional

import numpy as np
from sklearn.metrics import precision_recall_curve


def find_threshold_by_cost(
    y_true: np.ndarray,
    y_score: np.ndarray,
    cost_fp: float = 1.0,
    cost_fn: float = 5.0,
) -> Tuple[float, float]:
    """
    Find the threshold that minimises total cost.

    Parameters
    ----------
    y_true : true binary labels (0/1)
    y_score : predicted probabilities
    cost_fp : cost of a false positive (e.g., investigation cost)
    cost_fn : cost of a false negative (e.g., fraud loss)

    Returns
    -------
    best_threshold : float
    min_cost : float
    """
    thresholds = np.linspace(0.01, 0.99, 200)
    best_t = 0.5
    min_cost = np.inf

    for t in thresholds:
        y_pred = (y_score >= t).astype(int)
        fp = ((y_pred == 1) & (y_true == 0)).sum()
        fn = ((y_pred == 0) & (y_true == 1)).sum()
        cost = fp * cost_fp + fn * cost_fn
        if cost < min_cost:
            min_cost = cost
            best_t = t

    return best_t, min_cost


def threshold_for_recall(
    y_true: np.ndarray,
    y_score: np.ndarray,
    target_recall: float,
    max_fpr: Optional[float] = None,
) -> float:
    """
    Find the threshold that achieves a desired recall, optionally limiting FPR.

    Parameters
    ----------
    y_true : true binary labels
    y_score : predicted probabilities
    target_recall : desired recall (between 0 and 1)
    max_fpr : optional maximum false positive rate to allow

    Returns
    -------
    threshold : float
    """
    precisions, recalls, thresholds = precision_recall_curve(y_true, y_score)
    # thresholds array is one element shorter; we use it to map recall to threshold
    # recall[i] is recall for thresholds[i]
    valid = recalls >= target_recall
    if not valid.any():
        raise ValueError(f"Cannot achieve recall {target_recall} with any threshold")

    # Find indices where recall is at least target
    cand_indices = np.where(valid)[0]
    # Among those, pick the one with highest threshold (lowest FPR typically)
    best_idx = cand_indices[-1]  # last index with recall >= target
    threshold = thresholds[best_idx]

    # If max_fpr is given, check FPR
    if max_fpr is not None:
        # Compute FPR at this threshold
        y_pred = (y_score >= threshold).astype(int)
        fp = ((y_pred == 1) & (y_true == 0)).sum()
        tn = ((y_pred == 0) & (y_true == 0)).sum()
        fpr = fp / (fp + tn) if (fp + tn) > 0 else 0.0
        if fpr > max_fpr:
            # Need a stricter threshold
            # Recompute with FPR constraint
            # This is a simplified approach; a more thorough search could be done
            thresholds2 = np.linspace(threshold, 0.99, 100)
            best_t2 = threshold
            min_fpr_diff = np.inf
            for t2 in thresholds2:
                y_pred2 = (y_score >= t2).astype(int)
                fp2 = ((y_pred2 == 1) & (y_true == 0)).sum()
                tn2 = ((y_pred2 == 0) & (y_true == 0)).sum()
                fpr2 = fp2 / (fp2 + tn2) if (fp2 + tn2) > 0 else 0.0
                recall2 = ((y_pred2 == 1) & (y_true == 1)).sum() / (y_true == 1).sum()
                if recall2 >= target_recall and fpr2 <= max_fpr:
                    return t2
                elif recall2 >= target_recall:
                    # FPR too high, move to higher threshold
                    continue
                else:
                    # recall already dropped below target, stop
                    break
            # If no threshold meets both, return best that meets recall
            return threshold

    return threshold


def adjust_threshold_with_business_rules(
    base_threshold: float,
    transaction_amount: float,
    amount_threshold: float = 1000.0,
    recall_boost: float = 0.1,
) -> float:
    """
    Lower the threshold for high‑value transactions to increase recall.

    Parameters
    ----------
    base_threshold : original threshold (e.g., from model training)
    transaction_amount : amount of the current transaction
    amount_threshold : amount above which we apply a lower threshold
    recall_boost : fraction by which to lower the threshold
                   (e.g., 0.1 means threshold becomes base_threshold * (1 - recall_boost))

    Returns
    -------
    adjusted_threshold : float
    """
    if transaction_amount > amount_threshold:
        return base_threshold * (1 - recall_boost)
    return base_threshold
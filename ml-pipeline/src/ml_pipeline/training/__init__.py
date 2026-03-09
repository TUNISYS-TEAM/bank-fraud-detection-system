"""
Training module for fraud detection models.

Provides:
- train_models: Main orchestrator for training all models defined in config.
- train_supervised: Train a single supervised model with CV.
- validate_pipeline: Sanity checks for leakage and signal.
"""

from .train_models import main as train_models_main
from .train_supervised import main as train_supervised_main
from .validate_pipeline import (
    check_auc_leakage,
    check_as_of_correctness,
    quick_signal_test,
)

__all__ = [
    "train_models_main",
    "train_supervised_main",
    "check_auc_leakage",
    "check_as_of_correctness",
    "quick_signal_test",
]
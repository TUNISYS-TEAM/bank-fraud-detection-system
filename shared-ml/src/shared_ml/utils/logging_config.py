"""
Centralised logging setup for the fraud ML pipeline.

Call setup_logging() once at the top of your training script or notebook.
All pipeline modules use logging.getLogger(__name__) and will inherit
this configuration automatically.

Usage
-----
    from shared_ml.utils.logging_config import setup_logging
    setup_logging(level="INFO", log_file="logs/pipeline.log")
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path


def setup_logging(
    level: str = "INFO",
    log_file: str | Path | None = None,
    fmt: str = "%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
    datefmt: str = "%Y-%m-%d %H:%M:%S",
) -> logging.Logger:
    """
    Configure root logger with console handler (+ optional file handler).

    Parameters
    ----------
    level    : logging level string ('DEBUG', 'INFO', 'WARNING', 'ERROR')
    log_file : optional path to a log file (rotated externally)
    fmt      : log format string
    datefmt  : date format string

    Returns
    -------
    Root logger
    """
    numeric_level = getattr(logging, level.upper(), logging.INFO)
    formatter     = logging.Formatter(fmt, datefmt=datefmt)

    root = logging.getLogger()
    root.setLevel(numeric_level)

    # Remove existing handlers to avoid duplicate logs in Jupyter
    root.handlers.clear()

    # Console handler
    ch = logging.StreamHandler(sys.stdout)
    ch.setLevel(numeric_level)
    ch.setFormatter(formatter)
    root.addHandler(ch)

    # Optional file handler
    if log_file:
        log_path = Path(log_file)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        fh = logging.FileHandler(log_path, mode="a", encoding="utf-8")
        fh.setLevel(numeric_level)
        fh.setFormatter(formatter)
        root.addHandler(fh)

    # Suppress noisy third-party loggers
    for noisy in ("matplotlib", "PIL", "urllib3", "sklearn"):
        logging.getLogger(noisy).setLevel(logging.WARNING)

    root.info("Logging initialised — level=%s%s",
              level, f", file={log_file}" if log_file else "")
    return root
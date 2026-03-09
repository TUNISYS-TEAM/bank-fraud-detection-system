"""
Configuration module for the ML pipeline.

This module provides:
- ModelConfig: Load model hyperparameters from JSON (managed by Spring Boot UI)
- settings: Global application settings from environment variables
- schemas: Pydantic models for data validation
"""

from .model_config import ModelConfig
from .settings import settings
from . import schemas

__all__ = [
    "ModelConfig",    # Main config class for model hyperparameters
    "settings",       # Global settings (paths, DB connections, etc.)
    "schemas",        # Data validation schemas
]

# Version of the configuration module
__version__ = "1.0.0"
from __future__ import annotations

from pathlib import Path
from typing import Optional

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """
    Runtime settings for the ML pipeline.

    - Reads values from environment variables and optional .env file
    - Centralizes paths, storage settings, database settings, and logging
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        case_sensitive=False,
        extra="ignore"
    )

    # -------------------- Project root --------------------
    project_root: Path = Field(default_factory=lambda: Path(__file__).resolve().parents[3])

    # -------------------- Relative paths --------------------
    data_raw: Path = Field(default=Path("data/raw"))
    data_processed: Path = Field(default=Path("data/processed"))
    artifacts_models: Path = Field(default=Path("artifacts/exported_models"))
    artifacts_onnx: Path = Field(default=Path("artifacts/exported_onnx"))
    artifacts_logs: Path = Field(default=Path("artifacts/logs"))

    # -------------------- Config paths --------------------
    model_config_json: Path = Field(default=Path("src/ml_pipeline/config/model_config.json"))

    # -------------------- Global runtime --------------------
    random_seed: int = Field(default=42)
    log_level: str = Field(default="INFO")

    # -------------------- PostgreSQL --------------------
    postgres_host: str = Field(default="localhost")
    postgres_port: int = Field(default=5432)
    postgres_db: str = Field(default="fraud_db")
    postgres_user: str = Field(default="postgres")
    postgres_password: str = Field(default="admin")

    # -------------------- MinIO --------------------
    minio_endpoint: str = Field(default="localhost:9000")
    minio_access_key: str = Field(default="minioadmin")
    minio_secret_key: str = Field(default="minioadmin123")
    minio_bucket: str = Field(default="fraud-datalake")
    minio_secure: bool = Field(default=False)

    # -------------------- MLflow (optional) --------------------
    mlflow_tracking_uri: Optional[str] = Field(default=None)
    mlflow_experiment: str = Field(default="fraud_detection")

    @property
    def data_raw_path(self) -> Path:
        return self.project_root / self.data_raw

    @property
    def data_processed_path(self) -> Path:
        return self.project_root / self.data_processed

    @property
    def artifacts_models_path(self) -> Path:
        return self.project_root / self.artifacts_models

    @property
    def artifacts_onnx_path(self) -> Path:
        return self.project_root / self.artifacts_onnx

    @property
    def artifacts_logs_path(self) -> Path:
        return self.project_root / self.artifacts_logs

    @property
    def model_config_json_path(self) -> Path:
        return self.project_root / self.model_config_json

    @property
    def postgres_dsn(self) -> str:
        return (
            f"postgresql://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )


settings = Settings()
from pydantic_settings import BaseSettings
from pydantic import Field
from typing import Optional


class Settings(BaseSettings):
    # -------------------- Paths --------------------
    project_root: str = Field("/app", env="PROJECT_ROOT")
    data_raw: str = Field("data/raw", env="DATA_RAW")
    data_processed: str = Field("data/processed", env="DATA_PROCESSED")
    artifacts_models: str = Field("artifacts/exported_models", env="ARTIFACTS_MODELS")
    artifacts_onnx: str = Field("artifacts/exported_onnx", env="ARTIFACTS_ONNX")
    artifacts_logs: str = Field("artifacts/logs", env="ARTIFACTS_LOGS")

    # -------------------- PostgreSQL --------------------
    postgres_host: str = Field("localhost", env="POSTGRES_HOST")
    postgres_port: int = Field(5432, env="POSTGRES_PORT")
    postgres_db: str = Field("fraud_db", env="POSTGRES_DB")
    postgres_user: str = Field("postgres", env="POSTGRES_USER")
    postgres_password: str = Field("admin", env="POSTGRES_PASSWORD")

    @property
    def postgres_dsn(self) -> str:
        return f"postgresql://{self.postgres_user}:{self.postgres_password}@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"

    # -------------------- MinIO --------------------
    minio_endpoint: str = Field("localhost:9000", env="MINIO_ENDPOINT")
    minio_access_key: str = Field("minioadmin", env="MINIO_ACCESS_KEY")
    minio_secret_key: str = Field("minioadmin123", env="MINIO_SECRET_KEY")
    minio_bucket: str = Field("fraud-datalake", env="MINIO_BUCKET")
    minio_secure: bool = Field(False, env="MINIO_SECURE")

    # -------------------- MLflow (optional) --------------------
    mlflow_tracking_uri: Optional[str] = Field(None, env="MLFLOW_TRACKING_URI")
    mlflow_experiment: str = Field("fraud_detection", env="MLFLOW_EXPERIMENT")

    # -------------------- Logging --------------------
    log_level: str = Field("INFO", env="LOG_LEVEL")

    class Config:
        env_file = ".env"
        case_sensitive = False


# Create a global instance
settings = Settings()
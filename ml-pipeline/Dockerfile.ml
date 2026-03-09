FROM python:3.9-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    g++ \
    make \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create a non‑root user
RUN useradd --create-home --shell /bin/bash appuser \
    && chown -R appuser:appuser /app
USER appuser

# Copy requirements first (better caching)
COPY --chown=appuser:appuser requirements-ml.txt .
RUN pip install --no-cache-dir -r requirements-ml.txt

# Copy source code and scripts
COPY --chown=appuser:appuser src/ ./src/
COPY --chown=appuser:appuser scripts/ ./scripts/
COPY --chown=appuser:appuser pyproject.toml .

# Create necessary directories (will be mounted as volumes)
RUN mkdir -p exported_models exported_onnx logs data test_models test_onnx exported_parquet

# Set Python path
ENV PYTHONPATH=/app/src

# Default command: run quick_test and keep container alive
CMD ["sh", "-c", "python scripts/quick_test.py && tail -f /dev/null"]
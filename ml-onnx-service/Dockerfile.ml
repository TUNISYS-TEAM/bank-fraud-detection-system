FROM python:3.11-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    g++ \
    make \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN useradd --create-home --shell /bin/bash appuser

# Copy dependency files first for better cache
COPY requirements-ml.txt .
COPY pyproject.toml .

# Installer shared-ml (via contexte additionnel BuildKit)
COPY --from=shared-ml . /shared-ml
RUN pip install --no-cache-dir /shared-ml

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements-ml.txt

# Copy project source
COPY . .

# Install package (ml-onnx-service mapped to app folder)
RUN pip install --no-cache-dir -e .

# Create runtime directories
RUN mkdir -p \
    /app/artifacts/exported_models \
    /app/artifacts/exported_onnx \
    /app/artifacts/exported_parquet \
    /app/artifacts/reports \
    /app/artifacts/logs \
    /app/data/raw \
    /app/data/processed \
    /app/data/samples \
    /app/output

# Fix permissions
RUN chown -R appuser:appuser /app

# Switch to non-root
USER appuser

# Set Python path
ENV PYTHONPATH=/app/src:/app

# Keep container alive
CMD ["tail", "-f", "/dev/null"]
FROM python:3.10-slim

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

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements-ml.txt

# Copy project source
COPY . .

# Install package
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
    /app/data/samples

# Fix permissions
RUN chown -R appuser:appuser /app

# Switch to non-root
USER appuser

# Set Python path
ENV PYTHONPATH=/app/src

# Expose le port de l'API
EXPOSE 8888

CMD ["python", "scripts/quick_test.py"]
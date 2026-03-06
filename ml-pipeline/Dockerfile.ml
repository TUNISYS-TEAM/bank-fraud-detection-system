# ml-pipeline/Dockerfile.ml
FROM python:3.9-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    g++ \
    make \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first for better caching
COPY requirements-ml.txt .
RUN pip install --no-cache-dir -r requirements-ml.txt

# Copy the entire project
COPY . .

# Create necessary directories
RUN mkdir -p exported_models exported_onnx logs data test_models test_onnx exported_parquet

# Make scripts executable - only if they exist
RUN if [ -d "training" ]; then chmod +x training/*.py 2>/dev/null || true; fi
RUN if [ -f "quick_test.py" ]; then chmod +x quick_test.py; fi
RUN if [ -f "test_onnx_export.py" ]; then chmod +x test_onnx_export.py; fi
RUN if [ -f "data/generate_sample_data.py" ]; then chmod +x data/generate_sample_data.py; fi

# Set Python path
ENV PYTHONPATH=/app

# Default command
CMD ["python", "quick_test.py"]
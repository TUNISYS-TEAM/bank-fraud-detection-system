FROM python:3.11-slim

WORKDIR /app

# Dépendances système pour PyTorch (CPU) et autres librairies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libgomp1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

# Installer shared-ml (via contexte additionnel BuildKit)
COPY --from=shared-ml . /shared-ml
RUN pip install --no-cache-dir /shared-ml

# Installer les dépendances Python (dont shap, numpy, etc.)
RUN pip install --no-cache-dir -r requirements.txt

# Installer PyTorch (version CPU) explicitement
RUN pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu

COPY . .

# Configuration de l'environnement
ENV PYTHONPATH=/app
ENV PYTHONUNBUFFERED=1

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
from fastapi import FastAPI
from app.config import settings
from app.inference.inference import router as inference_router
from app.models.model_registry import registry

app = FastAPI(title="Fraud Hybrid Inference Service", version="1.0.0")
app.include_router(inference_router, tags=["inference"])

@app.get("/health")
async def health():
    return {"status": "healthy", "service": "fraud-ml-hybrid"}

@app.get("/models")
async def list_models():
    """Liste les modèles actuellement chargés en cache mémoire."""
    return {
        "loaded_models_count": len(registry.models),
        "loaded_uuids": list(registry.models.keys())
    }
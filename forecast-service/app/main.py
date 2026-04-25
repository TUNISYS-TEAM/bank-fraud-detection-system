import logging
import time
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from app.api.geo_kpi_routes import router as kpi_router
from apscheduler.schedulers.background import BackgroundScheduler
from app.training.data_fetcher import fetch_training_data
from app.training.model_store import retrain_and_replace
from app.cache.memory_cache import cache

# Logging structuré
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Fraud Intelligence Service",
    version="2.0.0",
    description="Service de prévision de KPIs de fraude – Production Ready"
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:4200", "http://localhost:8100"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Middleware de logging des requêtes
@app.middleware("http")
async def log_requests(request: Request, call_next):
    start_time = time.time()
    response = await call_next(request)
    duration = time.time() - start_time
    logger.info("%s %s → %d (%.3fs)", request.method, request.url.path, response.status_code, duration)
    return response

# Health check
@app.get("/api/v1/health", tags=["health"])
@app.get("/health", tags=["health"])
async def health_check():
    import os
    from app.config import POSTAL_CODES, MODEL_PATH
    models_exist = os.path.isdir(MODEL_PATH) and any(f.endswith('.pkl') for f in os.listdir(MODEL_PATH))
    return {
        "status": "healthy",
        "version": "2.0.0",
        "postal_codes_loaded": len(POSTAL_CODES),
        "models_available": models_exist,
        "cache_size": cache.size,
    }

app.include_router(kpi_router, prefix="/api/v1", tags=["kpis"])

TRAINING_WINDOW_DAYS = 850


def scheduled_training():
    logger.info("Starting scheduled training...")
    try:
        data = fetch_training_data(window_days=TRAINING_WINDOW_DAYS)
        transactions = data.get("transactions", [])
        logger.info("Fetched %d transactions for training", len(transactions))
        if not transactions:
            logger.warning("No training data fetched, skipping.")
            return
        results = retrain_and_replace(transactions)
        logger.info("Scheduled training completed: %s", results)
        cache.clear()
    except Exception as e:
        logger.error("Scheduled training failed: %s", e, exc_info=True)


scheduler = BackgroundScheduler()
scheduler.add_job(scheduled_training, 'cron', day_of_week='sun', hour=3)
scheduler.start()
logger.info("Scheduler started: training every Sunday at 3:00 AM")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000, log_level="info")
from fastapi import FastAPI
from api.health_routes import router as health_router
from api.geo_kpi_routes import router as kpi_router

app = FastAPI(title="Fraud Intelligence Service", version="1.0.0")

app.include_router(health_router, prefix="/api/v1", tags=["health"])
app.include_router(kpi_router, prefix="/api/v1", tags=["kpis"])

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
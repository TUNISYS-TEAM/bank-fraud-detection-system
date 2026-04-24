from fastapi import APIRouter, BackgroundTasks, HTTPException
from schemas.requests import OverviewZoneRequest
from schemas.responses import OverviewZoneResponse
from services.geo_kpi_service import compute_overview_zone, compute_all_kpis_for_region
from cache.memory_cache import cache
from config import POSTAL_CODES, DEFAULT_HORIZON_DAYS
from typing import Optional, List

router = APIRouter()

@router.post("/kpi/overview-zone", response_model=OverviewZoneResponse)
async def overview_zone(request: OverviewZoneRequest):
    """
    Endpoint principal pour l'interaction avec la carte.
    Reçoit postalCode, region, period et retourne tous les KPIs de la zone.
    """
    # Vérification que le code postal est dans la liste configurée (optionnel)
    if request.postalCode not in POSTAL_CODES:
        raise HTTPException(status_code=400, detail="Code postal non reconnu")

    # Pour l'instant on ne distingue pas la period autrement que par l'horizon
    # On extrait "next_7_days", "next_30_days" etc.
    if request.period.startswith("next_"):
        try:
            days = int(request.period.split("_")[1])
        except (IndexError, ValueError):
            days = 7
    else:
        days = DEFAULT_HORIZON_DAYS

    response = compute_overview_zone(
        postal_code=request.postalCode,
        region=request.region,
        period=request.period,
        horizon_days=days
    )
    return response

@router.get("/kpis")
async def get_kpis(
    postal_code: str = "75001",
    horizon_days: int = DEFAULT_HORIZON_DAYS
):
    """Ancien endpoint conservé pour compatibilité."""
    if postal_code not in POSTAL_CODES:
        raise HTTPException(status_code=400, detail="Code postal non reconnu")
    return compute_all_kpis_for_region(postal_code, horizon_days)

@router.post("/refresh")
async def refresh_predictions(
    background_tasks: BackgroundTasks,
    horizon_days: Optional[int] = DEFAULT_HORIZON_DAYS,
    regions: Optional[List[str]] = None
):
    """Déclenche le recalcul des prédictions pour toutes les régions."""
    if regions is None:
        zones = POSTAL_CODES
    else:
        zones = [z for z in regions if z in POSTAL_CODES]
        if not zones:
            raise HTTPException(status_code=400, detail="Aucune région valide")

    async def recompute():
        for zone in zones:
            kpis = compute_all_kpis_for_region(zone, horizon_days)
            cache_key = f"{zone}_{horizon_days}"
            cache.set(cache_key, kpis.dict())

    background_tasks.add_task(recompute)
    return {"status": "refresh started", "regions": zones, "horizon_days": horizon_days}
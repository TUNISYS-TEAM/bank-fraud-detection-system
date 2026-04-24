from typing import List, Optional

from fastapi import APIRouter, BackgroundTasks, HTTPException

from app.cache.memory_cache import cache
from app.config import DEFAULT_HORIZON_DAYS, POSTAL_CODES
from app.schemas.requests import OverviewZoneRequest
from app.schemas.responses import OverviewZoneResponse
from app.services.geo_kpi_service import compute_all_kpis_for_region, compute_overview_zone

router = APIRouter()

DEFAULT_POSTAL_CODE = POSTAL_CODES[0] if POSTAL_CODES else "1000"


def parse_horizon_days(period: str) -> int:
    """
    Convertit une période de type 'next_7_days' en horizon numérique.
    Si la période est absente ou invalide, on utilise l'horizon par défaut.
    """
    if not period or not period.startswith("next_"):
        return DEFAULT_HORIZON_DAYS

    parts = period.split("_")

    if len(parts) >= 3 and parts[1].isdigit():
        return int(parts[1])

    return DEFAULT_HORIZON_DAYS


def build_cache_key(postal_code: str, horizon_days: int) -> str:
    """
    Construit une clé de cache unique pour une zone et un horizon.
    Cette clé est partagée par /kpi/overview-zone, /kpis et /refresh.
    """
    return f"kpi:{postal_code}:{horizon_days}"


@router.post("/kpi/overview-zone", response_model=OverviewZoneResponse)
async def overview_zone(request: OverviewZoneRequest):
    """
    Endpoint principal utilisé par la carte de la page Overview.

    Il reçoit une zone géographique sélectionnée et retourne les KPI prédictifs
    associés à cette zone.
    """
    if request.postalCode not in POSTAL_CODES:
        raise HTTPException(status_code=400, detail="Code postal non reconnu")

    horizon_days = parse_horizon_days(request.period)

    if horizon_days <= 0:
        raise HTTPException(status_code=400, detail="Horizon invalide")

    cache_key = build_cache_key(request.postalCode, horizon_days)

    cached_data = cache.get(cache_key)
    if cached_data is not None:
        return OverviewZoneResponse(**cached_data)

    response = compute_overview_zone(
        postal_code=request.postalCode,
        region=request.region,
        period=request.period,
        horizon_days=horizon_days,
    )

    cache.set(cache_key, response.model_dump())

    return response


@router.get("/kpis")
async def get_kpis(
    postal_code: Optional[str] = None,
    horizon_days: int = DEFAULT_HORIZON_DAYS,
):
    """
    Endpoint technique conservé pour tester rapidement les KPI d'une zone.
    """
    postal_code = postal_code or DEFAULT_POSTAL_CODE

    if postal_code not in POSTAL_CODES:
        raise HTTPException(status_code=400, detail="Code postal non reconnu")

    if horizon_days <= 0:
        raise HTTPException(status_code=400, detail="Horizon invalide")

    cache_key = build_cache_key(postal_code, horizon_days)

    cached_data = cache.get(cache_key)
    if cached_data is not None:
        return cached_data

    response = compute_all_kpis_for_region(postal_code, horizon_days)
    response_dict = response.model_dump()

    cache.set(cache_key, response_dict)

    return response_dict


@router.post("/refresh")
async def refresh_predictions(
    background_tasks: BackgroundTasks,
    horizon_days: Optional[int] = None,
    postal_codes: Optional[List[str]] = None,
):
    """
    Déclenche le recalcul des KPI pour une liste de codes postaux.

    Les résultats sont stockés dans le cache avec la même clé que celle utilisée
    par l'endpoint principal /kpi/overview-zone.
    """
    horizon_days = horizon_days or DEFAULT_HORIZON_DAYS

    if horizon_days <= 0:
        raise HTTPException(status_code=400, detail="Horizon invalide")

    if postal_codes is None:
        zones = POSTAL_CODES
    else:
        zones = [code for code in postal_codes if code in POSTAL_CODES]

        if not zones:
            raise HTTPException(status_code=400, detail="Aucun code postal valide")

    def recompute() -> None:
        temp_cache = {}

        for zone in zones:
            kpis = compute_all_kpis_for_region(zone, horizon_days)
            response_dict = kpis.model_dump()
            cache_key = build_cache_key(zone, horizon_days)
            temp_cache[cache_key] = response_dict

        if hasattr(cache, "replace_many"):
            cache.replace_many(temp_cache)
        else:
            cache.clear()
            for key, value in temp_cache.items():
                cache.set(key, value)

    background_tasks.add_task(recompute)

    return {
        "status": "refresh_started",
        "postalCodes": zones,
        "horizonDays": horizon_days,
    }
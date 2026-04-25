from typing import List, Optional

from fastapi import APIRouter, BackgroundTasks, HTTPException
from fastapi.responses import JSONResponse

from app.cache.memory_cache import cache
from app.config import DEFAULT_HORIZON_DAYS, POSTAL_CODES
from app.schemas.requests import OverviewZoneRequest, RefreshRequest
from app.schemas.responses import OverviewZoneResponse
from app.services.geo_kpi_service import compute_all_kpis_for_region, compute_overview_zone

router = APIRouter()

DEFAULT_POSTAL_CODE = POSTAL_CODES[0] if POSTAL_CODES else "1000"

# Rate limiting simple (compteur en mémoire)
_request_counters: dict = {}


def _check_rate_limit(client_ip: str, max_requests: int = 60, window_seconds: int = 60) -> None:
    """Rate limiting simple : max_requests par fenêtre glissante."""
    import time
    now = time.time()
    if client_ip not in _request_counters:
        _request_counters[client_ip] = []
    _request_counters[client_ip] = [t for t in _request_counters[client_ip] if now - t < window_seconds]
    if len(_request_counters[client_ip]) >= max_requests:
        raise HTTPException(status_code=429, detail="Trop de requêtes. Veuillez patienter.")
    _request_counters[client_ip].append(now)


def parse_horizon_days(period: str) -> int:
    """
    Convertit une période de type 'next_7_days' en horizon numérique.
    """
    if not period or not period.startswith("next_"):
        return DEFAULT_HORIZON_DAYS
    parts = period.split("_")
    if len(parts) >= 3 and parts[1].isdigit():
        return int(parts[1])
    return DEFAULT_HORIZON_DAYS


def build_cache_key(postal_code: str, horizon_days: int) -> str:
    """Clé de cache unique pour une zone et un horizon."""
    return f"kpi:{postal_code}:{horizon_days}"


@router.post("/kpi/overview-zone", response_model=OverviewZoneResponse)
async def overview_zone(request: OverviewZoneRequest):
    """
    Endpoint principal utilisé par la carte de la page Overview.
    """
    if request.postalCode and request.postalCode not in POSTAL_CODES:
        raise HTTPException(status_code=400, detail="Code postal non reconnu")

    horizon_days = parse_horizon_days(request.period)

    if horizon_days <= 0:
        raise HTTPException(status_code=400, detail="Horizon invalide")

    cache_key = build_cache_key(request.postalCode or "GLOBAL", horizon_days)

    cached_data = cache.get(cache_key)
    if cached_data is not None:
        return JSONResponse(
            content=cached_data,
            headers={"Cache-Control": "public, max-age=300", "X-Cache": "HIT"}
        )

    response = compute_overview_zone(
        postal_code=request.postalCode,
        region=request.region,
        period=request.period,
        horizon_days=horizon_days,
    )

    response_dict = response.model_dump()
    cache.set(cache_key, response_dict)

    return JSONResponse(
        content=response_dict,
        headers={"Cache-Control": "public, max-age=300", "X-Cache": "MISS"}
    )


@router.get("/kpis")
async def get_kpis(
    postal_code: Optional[str] = None,
    horizon_days: int = DEFAULT_HORIZON_DAYS,
):
    """Endpoint technique pour tester les KPI d'une zone ou obtenir les KPIs globaux."""
    if horizon_days <= 0:
        raise HTTPException(status_code=400, detail="Horizon invalide")

    cache_key = build_cache_key(postal_code or "GLOBAL", horizon_days)

    cached_data = cache.get(cache_key)
    if cached_data is not None:
        return JSONResponse(
            content=cached_data,
            headers={"Cache-Control": "public, max-age=300", "X-Cache": "HIT"}
        )

    if postal_code:
        if postal_code not in POSTAL_CODES:
            raise HTTPException(status_code=400, detail="Code postal non reconnu")
        response = compute_all_kpis_for_region(postal_code, horizon_days)
    else:
        response = compute_overview_zone(
            postal_code=None,
            region="Tunisie",
            period=f"next_{horizon_days}_days",
            horizon_days=horizon_days,
        )

    response_dict = response.model_dump()
    cache.set(cache_key, response_dict)

    return JSONResponse(
        content=response_dict,
        headers={"Cache-Control": "public, max-age=300", "X-Cache": "MISS"}
    )


@router.post("/refresh")
async def refresh_predictions(
    background_tasks: BackgroundTasks,
    body: RefreshRequest = None,
):
    """
    Déclenche le recalcul des KPI pour une liste de codes postaux.
    Accepte un body JSON optionnel : {"horizon_days": 7, "postalCodes": ["6131", ...]}
    Si aucun body n'est fourni, recalcule toutes les zones avec l'horizon par défaut.
    """
    if body is None:
        horizon_days = DEFAULT_HORIZON_DAYS
        zones = POSTAL_CODES
    else:
        horizon_days = body.horizon_days or DEFAULT_HORIZON_DAYS
        if body.postalCodes is None:
            zones = POSTAL_CODES
        else:
            zones = [code for code in body.postalCodes if code in POSTAL_CODES]
            if not zones:
                raise HTTPException(status_code=400, detail="Aucun code postal valide")

    if horizon_days <= 0:
        raise HTTPException(status_code=400, detail="Horizon invalide")

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

    return JSONResponse(
        content={
            "status": "refresh_started",
            "postalCodes": zones,
            "horizonDays": horizon_days,
        },
        headers={"Cache-Control": "no-cache"}
    )


@router.get("/cache/stats")
async def cache_stats():
    """Retourne les métriques du cache (hits, misses, hit rate)."""
    return JSONResponse(content=cache.get_stats())
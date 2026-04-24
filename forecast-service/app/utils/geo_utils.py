import math


def stable_postal_code_seed(postal_code: str) -> int:
    return sum(ord(char) for char in postal_code)


def haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    Calcule la distance en kilomètres entre deux points géographiques
    avec la formule de Haversine.
    """
    earth_radius_km = 6371.0

    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)

    a = (
        math.sin(dlat / 2) ** 2
        + math.cos(math.radians(lat1))
        * math.cos(math.radians(lat2))
        * math.sin(dlon / 2) ** 2
    )

    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

    return earth_radius_km * c


def is_cross_border(origin_code: str, dest_code: str) -> bool:
    """
    Détecte si une transaction est transfrontalière.

    Version V1 : retourne False par défaut, car les règles pays/région
    doivent être définies selon les données réelles disponibles.
    """
    return False
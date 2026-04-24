import math

def haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distance en km entre deux points (formule de haversine)."""
    R = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat/2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon/2)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))
    return R * c

def is_cross_border(origin_code: str, dest_code: str) -> bool:
    """
    Simule la détection de transaction transfrontalière.
    À remplacer par une vraie logique métier.
    """
    # Exemple simplifié : codes postaux dans des listes de pays différents.
    # Ici on suppose que les codes postaux nationaux sont "XX...".
    # Pour l'exemple, retourne False.
    return False
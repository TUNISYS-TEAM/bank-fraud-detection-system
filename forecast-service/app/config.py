import os
import json
from pathlib import Path
from typing import List

# Chemin vers le fichier des codes postaux (dans shared-ml monté)
POSTAL_CODES_FILE = "src/shared_ml/utils/resources/zip-postcodes.json"

def load_postal_codes_from_file(filepath: str) -> List[str]:
    path = Path(filepath)
    if not path.exists():
        raise FileNotFoundError(f"Postal codes file not found: {path}")
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    # Extraire les zip uniques (le fichier contient déjà des "zip")
    unique_zips = sorted({item["zip"] for item in data})
    return unique_zips

# Chargement automatique
if Path(POSTAL_CODES_FILE).exists():
    POSTAL_CODES: List[str] = load_postal_codes_from_file(POSTAL_CODES_FILE)
else:
    # Fallback pour les tests ou si le fichier est absent
    POSTAL_CODES: List[str] = os.getenv("POSTAL_CODES", "1000,1001,1002").split(",")

DEFAULT_HORIZON_DAYS: int = int(os.getenv("DEFAULT_HORIZON_DAYS", 7))
MODEL_PATH: str = os.getenv("MODEL_PATH", "./models/")
LOSS_MODEL_FILE: str = os.path.join(MODEL_PATH, "loss_forecaster.pkl")
HOTSPOT_MODEL_FILE: str = os.path.join(MODEL_PATH, "hotspot_predictor.pkl")
REVENUE_MODEL_FILE: str = os.path.join(MODEL_PATH, "revenue_impact_predictor.pkl")
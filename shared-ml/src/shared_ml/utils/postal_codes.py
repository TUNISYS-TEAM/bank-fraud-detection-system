"""
postal_codes.py
===============
Résout les codes postaux tunisiens en Gouvernorat et Délégation
en utilisant le jeu de données officiel zip-postcodes.json (4 868 entrées, 24 gouvernorates).

Remplace l'ancienne carte de préfixes à 2 chiffres codée en dur par des recherches exactes par code postal.

Usage
-----
    from shared_ml.utils.postal_codes import PostalCodeResolver

    resolver = PostalCodeResolver()                  # charge le JSON inclus
    resolver.get_gov("2036")                         # → "Ariana"
    resolver.get_deleg("2036")                       # → "La Soukra"
    resolver.get_region("2036")                      # → "Nord-Est"
    resolver.enrich_dataframe(df, "Customer_Postal_Code")

Mapping Gouvernorat → Région
-----------------------------
Les 24 gouvernorats de la Tunisie sont regroupés en 7 régions géographiques
(utilisées comme caractéristique comportementale `region` pour la détection de fraude) :

    Grand_Tunis   : Tunis, Ariana, Ben Arous, Mannouba
    Nord_Est      : Bizerte, Nabeul, Zaghouan
    Nord_Ouest    : Beja, Jendouba, Kef, Siliana
    Centre_Est    : Sousse, Monastir, Mahdia, Sfax
    Centre_Ouest  : Kairouan, Kasserine, Sidi Bouzid
    Sud_Est       : Gabes, Medenine, Tataouine
    Sud_Ouest     : Gafsa, Tozeur, Kebili
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from functools import lru_cache

import numpy as np
import pandas as pd

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Gouvernorat → région géographique
# ---------------------------------------------------------------------------
GOV_TO_REGION: dict[str, str] = {
    # Grand Tunis
    "Tunis":       "Grand_Tunis",
    "Ariana":      "Grand_Tunis",
    "Ben Arous":   "Grand_Tunis",
    "Mannouba":    "Grand_Tunis",
    # Nord-Est
    "Bizerte":     "Nord_Est",
    "Nabeul":      "Nord_Est",
    "Zaghouan":    "Nord_Est",
    # Nord-Ouest
    "Beja":        "Nord_Ouest",
    "Jendouba":    "Nord_Ouest",
    "Kef":         "Nord_Ouest",
    "Siliana":     "Nord_Ouest",
    # Centre-Est
    "Sousse":      "Centre_Est",
    "Monastir":    "Centre_Est",
    "Mahdia":      "Centre_Est",
    "Sfax":        "Centre_Est",
    # Centre-Ouest
    "Kairouan":    "Centre_Ouest",
    "Kasserine":   "Centre_Ouest",
    "Sidi Bouzid": "Centre_Ouest",
    # Sud-Est
    "Gabes":       "Sud_Est",
    "Medenine":    "Sud_Est",
    "Tataouine":   "Sud_Est",
    # Sud-Ouest
    "Gafsa":       "Sud_Ouest",
    "Tozeur":      "Sud_Ouest",
    "Kebili":      "Sud_Ouest",
}

ALL_REGIONS = sorted(set(GOV_TO_REGION.values()))   # 7 regions

# ---------------------------------------------------------------------------
# Default path — sits next to this file
# ---------------------------------------------------------------------------
_DEFAULT_JSON = Path(__file__).parent / "resources" / "zip_gov_map.json"


# ---------------------------------------------------------------------------
class PostalCodeResolver:
    """
    Résolveur sans dépendance, sécurisé pour les threads.
    Le JSON est chargé une fois à la construction et conservé en mémoire (~50 Ko).

    Paramètres
    ----------
    json_path : str | Path | None
        Chemin vers zip_gov_map.json. Par défaut, utilise le fichier inclus.
    unknown_gov    : str   gouvernorat de repli lorsque le code postal n'est pas trouvé
    unknown_region : str   région de repli lorsque le code postal n'est pas trouvé
    """

    def __init__(
        self,
        json_path: str | Path | None = None,
        unknown_gov: str    = "Unknown",
        unknown_region: str = "Unknown",
    ):
        self.unknown_gov    = unknown_gov
        self.unknown_region = unknown_region

        path = Path(json_path) if json_path else _DEFAULT_JSON
        self._zip_to_gov:   dict[str, str] = {}
        self._zip_to_deleg: dict[str, str] = {}
        self._load(path)

    # ------------------------------------------------------------------
    def _load(self, path: Path):
        if not path.exists():
            raise FileNotFoundError(
                f"PostalCodeResolver: zip map not found at {path}. "
                "Make sure zip_gov_map.json is bundled with the package."
            )
        with open(path, encoding="utf-8") as f:
            entries = json.load(f)

        for e in entries:
            z = str(e["zip"]).strip()
            self._zip_to_gov[z]   = e["gov"]
            self._zip_to_deleg[z] = e["deleg"]

        logger.info(
            "PostalCodeResolver loaded %d zip codes from %s",
            len(self._zip_to_gov), path,
        )

    # ------------------------------------------------------------------
    @lru_cache(maxsize=1024)
    def _normalise_zip(self, raw) -> str:
        """Normalise en une chaîne de 4 chiffres ; retourne une chaîne vide en cas d'échec."""
        try:
            return str(int(str(raw).strip())).zfill(4)
        except (ValueError, TypeError):
            return ""

    # ------------------------------------------------------------------
    def get_gov(self, postal_code) -> str:
        """Retourne le nom du Gouvernorat pour un code postal."""
        z = self._normalise_zip(postal_code)
        return self._zip_to_gov.get(z, self.unknown_gov)

    def get_deleg(self, postal_code) -> str:
        """Retourne le nom de la Délégation pour un code postal."""
        z = self._normalise_zip(postal_code)
        return self._zip_to_deleg.get(z, self.unknown_gov)

    def get_region(self, postal_code) -> str:
        """Retourne le groupe géographique de 7 régions pour un code postal."""
        gov = self.get_gov(postal_code)
        return GOV_TO_REGION.get(gov, self.unknown_region)

    def get_all(self, postal_code) -> dict[str, str]:
        """Return {'zip', 'gov', 'deleg', 'region'} for a postal code."""
        z   = self._normalise_zip(postal_code)
        gov = self._zip_to_gov.get(z, self.unknown_gov)
        return {
            "zip":    z,
            "gov":    gov,
            "deleg":  self._zip_to_deleg.get(z, self.unknown_gov),
            "region": GOV_TO_REGION.get(gov, self.unknown_region),
        }

    # ------------------------------------------------------------------
    def enrich_dataframe(
        self,
        df: pd.DataFrame,
        postal_col: str = "Customer_Postal_Code",
        add_gov: bool    = True,
        add_deleg: bool  = False,
        add_region: bool = True,
    ) -> pd.DataFrame:
        """
        Ajoute des colonnes gov / deleg / region à un DataFrame en une seule passe.

        Paramètres
        ----------
        df          : DataFrame de données (non modifié sur place)
        postal_col  : nom de la colonne du code postal
        add_gov     : ajouter la colonne 'gov'
        add_deleg   : ajouter la colonne 'deleg'
        add_region  : ajouter la colonne 'region' (utilisée par ClientBehaviorTransformer)
        """
        if postal_col not in df.columns:
            raise ValueError(
                f"PostalCodeResolver.enrich_dataframe: "
                f"column '{postal_col}' not found in DataFrame"
            )

        df = df.copy()
        zips = df[postal_col].apply(self._normalise_zip)

        if add_gov:
            df["gov"]    = zips.map(self._zip_to_gov).fillna(self.unknown_gov)
        if add_deleg:
            df["deleg"]  = zips.map(self._zip_to_deleg).fillna(self.unknown_gov)
        if add_region:
            govs         = zips.map(self._zip_to_gov).fillna(self.unknown_gov)
            df["region"] = govs.map(GOV_TO_REGION).fillna(self.unknown_region)

        unknown_count = (df["region"] == self.unknown_region).sum() if add_region else 0
        if unknown_count:
            logger.debug(
                "PostalCodeResolver: %d/%d rows had unrecognised postal codes",
                unknown_count, len(df),
            )

        return df

    # ------------------------------------------------------------------
    def coverage_report(self, df: pd.DataFrame, postal_col: str = "Customer_Postal_Code") -> dict:
        """Rapport rapide de couverture — utile pour les vérifications de qualité des données."""
        total  = len(df)
        zips   = df[postal_col].apply(self._normalise_zip)
        known  = zips.isin(self._zip_to_gov).sum()
        return {
            "total_rows":      total,
            "known_zips":      int(known),
            "unknown_zips":    int(total - known),
            "coverage_pct":    round(known / total * 100, 2) if total else 0,
            "unique_zips_seen":int(zips.nunique()),
        }


# ---------------------------------------------------------------------------
# Singleton au niveau du module (initialisation paresseuse, sécurisée pour les threads via le GIL)
# ---------------------------------------------------------------------------
_resolver: PostalCodeResolver | None = None


def get_resolver(json_path: str | Path | None = None) -> PostalCodeResolver:
    """Retourne le résolveur singleton du module (créé au premier appel)."""
    global _resolver
    if _resolver is None:
        _resolver = PostalCodeResolver(json_path)
    return _resolver


def process_customer_postal_fit(df_train):
    resolver = get_resolver()
    df_enriched = resolver.enrich_dataframe(df_train, 'Customer_Postal_Code', add_gov=False, add_deleg=False, add_region=True)
    return df_enriched['region'].astype('category').cat.categories

def process_customer_postal_transform(df, region_categories):
    resolver = get_resolver()
    df_enriched = resolver.enrich_dataframe(df, 'Customer_Postal_Code', add_gov=False, add_deleg=False, add_region=True)
    cat_region = pd.Categorical(df_enriched['region'], categories=region_categories)
    df_enriched['Customer_Region'] = cat_region.codes.astype(np.float32)
    return df_enriched.drop(['Customer_Postal_Code', 'region'], axis=1)

def process_merchant_postal_fit(df_train):
    resolver = get_resolver()
    df_enriched = resolver.enrich_dataframe(df_train, 'Merchant_Postal_Code', add_gov=False, add_deleg=False, add_region=True)
    return df_enriched['region'].astype('category').cat.categories

def process_merchant_postal_transform(df, merchant_region_categories):
    resolver = get_resolver()
    df_enriched = resolver.enrich_dataframe(df, 'Merchant_Postal_Code', add_gov=False, add_deleg=False, add_region=True)
    cat_region = pd.Categorical(df_enriched['region'], categories=merchant_region_categories)
    df_enriched['Merchant_Region'] = cat_region.codes.astype(np.float32)
    return df_enriched.drop(['Merchant_Postal_Code', 'region'], axis=1)
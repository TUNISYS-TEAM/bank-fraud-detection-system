import pytest
import pandas as pd
from shared_ml.utils.postal_codes import PostalCodeResolver, get_resolver, GOV_TO_REGION


class TestPostalCodeResolver:
    def test_resolver_singleton(self):
        r1 = get_resolver()
        r2 = get_resolver()
        assert r1 is r2

    def test_known_zip(self, postal_resolver):
        # Tunis centre
        assert postal_resolver.get_gov("1000") == "Tunis"
        assert postal_resolver.get_deleg("1000") == "Bab Bhar"
        assert postal_resolver.get_region("1000") == "Grand_Tunis"

        # Ariana, La Soukra
        assert postal_resolver.get_gov("2036") == "Ariana"
        assert postal_resolver.get_deleg("2036") == "La Soukra"
        assert postal_resolver.get_region("2036") == "Grand_Tunis"

    def test_unknown_zip(self, postal_resolver):
        assert postal_resolver.get_gov("0000") == "Unknown"
        assert postal_resolver.get_region("0000") == "Unknown"

    def test_normalize_zip(self, postal_resolver):
        assert postal_resolver._normalise_zip("2036") == "2036"
        assert postal_resolver._normalise_zip(2036) == "2036"
        assert postal_resolver._normalise_zip("036") == "0036"
        assert postal_resolver._normalise_zip("abc") == ""

    def test_enrich_dataframe(self, postal_resolver):
        df = pd.DataFrame({
            "postal": ["1000", "2036", "9999", None]
        })
        enriched = postal_resolver.enrich_dataframe(df, postal_col="postal", add_gov=True, add_region=True)
        assert "gov" in enriched.columns
        assert "region" in enriched.columns
        assert enriched.loc[0, "gov"] == "Tunis"
        assert enriched.loc[1, "gov"] == "Ariana"
        assert enriched.loc[2, "gov"] == "Unknown"
        # region mapping
        assert enriched.loc[0, "region"] == "Grand_Tunis"
        assert enriched.loc[1, "region"] == "Grand_Tunis"
        assert enriched.loc[2, "region"] == "Unknown"

    def test_coverage_report(self, postal_resolver):
        df = pd.DataFrame({"postal": ["1000", "2036", "0000"]})
        report = postal_resolver.coverage_report(df, "postal")
        assert report["known_zips"] == 2
        assert report["unknown_zips"] == 1
        assert report["coverage_pct"] == 66.67
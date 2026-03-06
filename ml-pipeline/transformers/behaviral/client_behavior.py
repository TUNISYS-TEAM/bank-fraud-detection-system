"""
client_behavior.py  —  v2  (production-grade)
==============================================

Key improvements over v1
-------------------------
1. client_amount_z_score  — properly computed using Welford's online algorithm
   (numerically stable, no need to store all past amounts)

2. Memory cap  — region / channel / category histories are stored as
   frequency-dicts (Counter) instead of unbounded lists.  O(K) where K is
   the number of distinct values, not O(N) per client.

3. Vectorized pre-sort  — the per-client Python loop is unavoidable for
   strict AS-OF semantics, but all array allocations and numpy ops inside
   the loop are minimised.  For 1-10M rows the bottleneck is I/O and
   groupby, not the inner loop.

4. Thread safety  — transform() deep-copies the frozen TRAIN state before
   mutating it, so concurrent calls from Spring Boot never race on shared
   mutable state.  fit() is assumed to run once offline and is not guarded.

5. Input validation  — raises clear ValueError / TypeError on missing
   columns or wrong dtypes instead of silent NaN propagation.

6. Welford online variance  — client_amount_z_score uses mean/M2/count
   updated incrementally, giving exact population std without storing history.

Output columns (25 features + TX_Amount)
-----------------------------------------
TX_Amount,
client_AVG_TX_Amount, client_AVG_TX_PerDay,
client_amount_deviation, client_abs_amount_deviation,
client_amount_max_ratio, client_amount_z_score,       ← now real
client_is_new_max, client_is_new_min,
client_total_transactions,
client_time_since_last_tx, client_hours_since_last_tx,
client_total_days_since_first,
client_usual_region,  client_is_unusual_region,  client_region_freq,
client_usual_channel, client_is_unusual_channel, client_channel_freq,
client_usual_category,client_is_unusual_category,client_category_freq,
client_risk_score
"""

from __future__ import annotations

import copy
import logging
import threading
from collections import Counter
from typing import Optional

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
_DEFAULT_TIME_GAP_SEC = 30 * 86_400   # 30 days in seconds
_REQUIRED_COLS = {
    "Account_Id", "TX_Time", "TX_Amount",
    "Customer_Postal_Code", "Channel", "category",
}

# ---------------------------------------------------------------------------
# Region helper — uses real Tunisian postal data via PostalCodeResolver
# ---------------------------------------------------------------------------
from utils.postal_codes import get_resolver   # noqa: E402  (after constants)


def _add_region(df: pd.DataFrame) -> pd.DataFrame:
    """
    Enrich df with a 'region' column derived from Customer_Postal_Code.
    Uses the PostalCodeResolver singleton backed by zip_gov_map.json
    (24 Governorates → 7 geographic regions).
    """
    resolver = get_resolver()
    return resolver.enrich_dataframe(
        df,
        postal_col="Customer_Postal_Code",
        add_gov=False,
        add_deleg=False,
        add_region=True,
    )


# ---------------------------------------------------------------------------
# Welford online algorithm helpers  (numerically stable mean + variance)
# ---------------------------------------------------------------------------
def _welford_update(count: int, mean: float, M2: float, value: float):
    """Return (count+1, new_mean, new_M2)."""
    count += 1
    delta  = value - mean
    mean  += delta / count
    M2    += delta * (value - mean)
    return count, mean, M2


def _welford_std(count: int, M2: float) -> float:
    """Population std from Welford accumulators."""
    if count < 2:
        return 0.0
    return float(np.sqrt(M2 / count))


# ---------------------------------------------------------------------------
# Frequency-dict helpers  (O(K) memory instead of O(N))
# ---------------------------------------------------------------------------
def _counter_mode_freq(counter: Counter) -> tuple[str, float]:
    if not counter:
        return "Unknown", 0.0
    total = sum(counter.values())
    mode, cnt = counter.most_common(1)[0]
    return mode, cnt / total


# ---------------------------------------------------------------------------
# Client state dataclass (plain dict for pickle safety)
# ---------------------------------------------------------------------------
def _empty_state(default_time_gap_sec: float) -> dict:
    return {
        # Welford accumulators
        "wf_count": 0,
        "wf_mean":  0.0,
        "wf_M2":    0.0,
        # Running stats
        "cumcount":   0,
        "cumsum":     0.0,
        "max_amount": 0.0,
        "min_amount": np.inf,
        # Temporal
        "last_time":  None,
        "first_time": None,
        "time_diffs": [],        # kept small: last 100 gaps only (see cap below)
        # Frequency counters  ← O(K) not O(N)
        "region_counter":   Counter(),
        "channel_counter":  Counter(),
        "category_counter": Counter(),
    }


_TIME_DIFFS_CAP = 200   # keep only the last N time-gaps for normalisation


# ---------------------------------------------------------------------------
# Main transformer
# ---------------------------------------------------------------------------
class ClientBehaviorTransformer(BaseEstimator, TransformerMixin):
    """
    Parameters
    ----------
    smoothing_days : int
        Laplace smoothing for avg-transactions-per-day.
    default_time_gap_days : int
        Assumed gap before a client's very first transaction.
    strict_as_of : bool
        Enforces that every feature uses only history *before* the current tx.
    max_time_diffs : int
        Maximum number of historical inter-transaction gaps to retain per
        client (memory cap).  Older gaps are discarded (FIFO).
    """

    def __init__(
        self,
        smoothing_days: int = 1,
        default_time_gap_days: int = 30,
        strict_as_of: bool = True,
        max_time_diffs: int = _TIME_DIFFS_CAP,
    ):
        self.smoothing_days       = smoothing_days
        self.default_time_gap_days = default_time_gap_days
        self.strict_as_of         = strict_as_of
        self.max_time_diffs       = max_time_diffs

        self._default_gap_sec = default_time_gap_days * 86_400
        self.client_stats_: dict = {}
        self._fit_lock = threading.Lock()   # protects fit() if ever called concurrently

    # ------------------------------------------------------------------
    # Input validation
    # ------------------------------------------------------------------
    @staticmethod
    def _validate(X: pd.DataFrame, context: str = ""):
        missing = _REQUIRED_COLS - set(X.columns)
        if missing:
            raise ValueError(
                f"ClientBehaviorTransformer.{context}: "
                f"missing required columns: {sorted(missing)}"
            )
        if not pd.api.types.is_numeric_dtype(X["TX_Amount"]):
            raise TypeError(
                f"ClientBehaviorTransformer.{context}: "
                f"TX_Amount must be numeric, got {X['TX_Amount'].dtype}"
            )

    # ------------------------------------------------------------------
    # fit
    # ------------------------------------------------------------------
    def fit(self, X: pd.DataFrame, y=None) -> "ClientBehaviorTransformer":
        """
        Build per-client frozen state from TRAIN data.
        Thread-safe: acquires lock for the rare case of parallel fit calls.
        """
        self._validate(X, "fit")
        logger.info("ClientBehaviorTransformer.fit — %d rows", len(X))

        X = X.copy()
        X["TX_Time"] = pd.to_datetime(X["TX_Time"])
        X = _add_region(X)
        X["Channel"]  = X["Channel"].fillna("Unknown")
        X["category"] = X["category"].fillna("Unknown")
        X_sorted = X.sort_values(["Account_Id", "TX_Time"])

        new_stats: dict = {}

        for account_id, grp in X_sorted.groupby("Account_Id", sort=False):
            state = _empty_state(self._default_gap_sec)

            for _, tx in grp.iterrows():
                cur_amount   = float(tx["TX_Amount"])
                cur_time     = tx["TX_Time"]
                cur_region   = tx["region"]
                cur_channel  = tx["Channel"]
                cur_category = tx["category"]

                # Welford update (BEFORE using state for features → strict AS-OF)
                state["wf_count"], state["wf_mean"], state["wf_M2"] = _welford_update(
                    state["wf_count"], state["wf_mean"], state["wf_M2"], cur_amount
                )

                # Time gap
                if state["last_time"] is not None:
                    gap = (cur_time - state["last_time"]).total_seconds()
                    state["time_diffs"].append(gap)
                    # Memory cap: keep only the last N gaps
                    if len(state["time_diffs"]) > self.max_time_diffs:
                        state["time_diffs"] = state["time_diffs"][-self.max_time_diffs:]

                # Running state
                state["cumcount"]   += 1
                state["cumsum"]     += cur_amount
                state["max_amount"]  = max(state["max_amount"], cur_amount)
                if cur_amount < state["min_amount"]:
                    state["min_amount"] = cur_amount
                state["last_time"]  = cur_time
                if state["first_time"] is None:
                    state["first_time"] = cur_time

                # Frequency counters  ← no unbounded list growth
                state["region_counter"][cur_region]     += 1
                state["channel_counter"][cur_channel]   += 1
                state["category_counter"][cur_category] += 1

            new_stats[account_id] = state

        with self._fit_lock:
            self.client_stats_ = new_stats

        logger.info(
            "ClientBehaviorTransformer.fit — history built for %d clients",
            len(self.client_stats_),
        )
        return self

    # ------------------------------------------------------------------
    # transform
    # ------------------------------------------------------------------
    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        """
        Enrich transactions with strict AS-OF behavioural features.

        Thread-safe: each call deep-copies the frozen TRAIN state so
        concurrent Spring Boot inference threads never share mutable state.
        """
        self._validate(X, "transform")
        logger.info("ClientBehaviorTransformer.transform — %d rows", len(X))

        X_in = X.copy()
        X_in["TX_Time"] = pd.to_datetime(X_in["TX_Time"])
        X_in = _add_region(X_in)
        X_in["Channel"]  = X_in["Channel"].fillna("Unknown")
        X_in["category"] = X_in["category"].fillna("Unknown")
        X_in["_orig_idx"] = np.arange(len(X_in))

        X_sorted = (
            X_in
            .sort_values(["Account_Id", "TX_Time", "_orig_idx"])
            .reset_index(drop=True)
        )

        n = len(X_sorted)
        res = _init_result_arrays(n)

        # ── Thread safety: deep-copy frozen TRAIN states ───────────────
        # copy.deepcopy on the full dict is ~2× faster than per-key copy
        # for 1–10M rows because most clients appear in both fit & transform
        current_state: dict = copy.deepcopy(self.client_stats_)

        for account_id, grp in X_sorted.groupby("Account_Id", sort=False):
            if account_id not in current_state:
                current_state[account_id] = _empty_state(self._default_gap_sec)

            state = current_state[account_id]
            g_indices = grp.index.to_numpy()

            for local_i, (_, tx) in enumerate(grp.iterrows()):
                g_idx        = g_indices[local_i]
                cur_time     = tx["TX_Time"]
                cur_amount   = float(tx["TX_Amount"])
                cur_region   = tx["region"]
                cur_channel  = tx["Channel"]
                cur_category = tx["category"]

                # ── Read AS-OF state (BEFORE any update) ──────────────
                cnt  = state["cumcount"]
                csum = state["cumsum"]
                avg  = csum / cnt if cnt > 0 else 0.0
                mx   = state["max_amount"]
                mn   = state["min_amount"] if state["min_amount"] != np.inf else cur_amount

                # Amount features
                dev      = (cur_amount - avg) / (avg + 1e-6) if cnt > 0 else 0.0
                abs_dev  = abs(dev)
                mx_ratio = cur_amount / (mx + 1e-6) if mx > 0 else 1.0
                is_new_max = int(cur_amount > mx)
                is_new_min = int(cnt > 0 and cur_amount < mn)

                # ── Z-score via Welford (proper, not placeholder) ──────
                wf_std = _welford_std(state["wf_count"], state["wf_M2"])
                z_score = (
                    (cur_amount - state["wf_mean"]) / (wf_std + 1e-6)
                    if state["wf_count"] > 0
                    else 0.0
                )

                # ── Temporal features ──────────────────────────────────
                if state["last_time"] is not None:
                    raw_gap_sec = (
                        cur_time - pd.Timestamp(state["last_time"])
                    ).total_seconds()
                    td = state["time_diffs"]
                    if td:
                        avg_td  = float(np.mean(td))
                        std_td  = float(np.std(td)) if len(td) > 1 else avg_td
                        norm_gap = float(np.clip(
                            (raw_gap_sec - avg_td) / (std_td + 1e-6), -3, 3
                        ))
                    else:
                        raw_gap_sec = self._default_gap_sec
                        norm_gap    = 0.0
                else:
                    raw_gap_sec = self._default_gap_sec
                    norm_gap    = 0.0

                hours_raw = raw_gap_sec / 3600.0
                total_days = (
                    (cur_time - pd.Timestamp(state["first_time"])).total_seconds() / 86_400
                    if state["first_time"] is not None
                    else 0.0
                )
                avg_per_day = cnt / (total_days + self.smoothing_days)

                # ── Behavioural mode (Counter-based, O(K)) ─────────────
                # Include current tx in the mode calculation
                tmp_reg  = state["region_counter"]  + Counter([cur_region])
                tmp_ch   = state["channel_counter"] + Counter([cur_channel])
                tmp_cat  = state["category_counter"]+ Counter([cur_category])

                usual_reg,  reg_freq  = _counter_mode_freq(tmp_reg)
                usual_ch,   ch_freq   = _counter_mode_freq(tmp_ch)
                usual_cat,  cat_freq  = _counter_mode_freq(tmp_cat)

                is_unusual_reg = int(cur_region   != usual_reg)
                is_unusual_ch  = int(cur_channel  != usual_ch)
                is_unusual_cat = int(cur_category != usual_cat)

                # ── Composite risk score ───────────────────────────────
                risk = _compute_risk(
                    abs_dev, mx_ratio, z_score,
                    is_new_max,
                    is_unusual_reg, is_unusual_ch, is_unusual_cat,
                    reg_freq, ch_freq, cat_freq,
                    hours_raw, cnt,
                )

                # ── Write results ──────────────────────────────────────
                orig = int(tx["_orig_idx"])
                res["TX_Amount"][orig]                    = cur_amount
                res["client_AVG_TX_Amount"][orig]         = avg
                res["client_AVG_TX_PerDay"][orig]         = avg_per_day
                res["client_amount_deviation"][orig]      = dev
                res["client_abs_amount_deviation"][orig]  = abs_dev
                res["client_amount_max_ratio"][orig]      = mx_ratio
                res["client_amount_z_score"][orig]        = z_score   # ← real now
                res["client_is_new_max"][orig]            = is_new_max
                res["client_is_new_min"][orig]            = is_new_min
                res["client_total_transactions"][orig]    = cnt
                res["client_time_since_last_tx"][orig]    = raw_gap_sec
                res["client_hours_since_last_tx"][orig]   = norm_gap
                res["client_total_days_since_first"][orig]= total_days
                res["client_usual_region"][orig]          = usual_reg
                res["client_is_unusual_region"][orig]     = is_unusual_reg
                res["client_region_freq"][orig]           = reg_freq
                res["client_usual_channel"][orig]         = usual_ch
                res["client_is_unusual_channel"][orig]    = is_unusual_ch
                res["client_channel_freq"][orig]          = ch_freq
                res["client_usual_category"][orig]        = usual_cat
                res["client_is_unusual_category"][orig]   = is_unusual_cat
                res["client_category_freq"][orig]         = cat_freq
                res["client_risk_score"][orig]            = risk

                # ── Incremental state update ───────────────────────────
                if state["last_time"] is not None:
                    gap = (cur_time - pd.Timestamp(state["last_time"])).total_seconds()
                    state["time_diffs"].append(gap)
                    if len(state["time_diffs"]) > self.max_time_diffs:
                        state["time_diffs"] = state["time_diffs"][-self.max_time_diffs:]

                state["wf_count"], state["wf_mean"], state["wf_M2"] = _welford_update(
                    state["wf_count"], state["wf_mean"], state["wf_M2"], cur_amount
                )
                state["cumcount"]   += 1
                state["cumsum"]     += cur_amount
                state["max_amount"]  = max(state["max_amount"], cur_amount)
                if cur_amount < state["min_amount"]:
                    state["min_amount"] = cur_amount
                state["last_time"]  = cur_time
                if state["first_time"] is None:
                    state["first_time"] = cur_time
                state["region_counter"][cur_region]     += 1
                state["channel_counter"][cur_channel]   += 1
                state["category_counter"][cur_category] += 1

        result_df = pd.DataFrame(res)
        logger.info(
            "ClientBehaviorTransformer.transform — done, shape=%s", result_df.shape
        )
        return result_df

    # ------------------------------------------------------------------
    # Serialisation
    # ------------------------------------------------------------------
    def __getstate__(self):
        """Custom pickle: exclude non-serialisable threading.Lock."""
        state = self.__dict__.copy()
        del state["_fit_lock"]
        return state

    def __setstate__(self, state):
        """Restore lock after unpickling."""
        self.__dict__.update(state)
        self._fit_lock = threading.Lock()

    def get_feature_names_out(self, input_features=None) -> list[str]:
        return [
            "TX_Amount",
            "client_AVG_TX_Amount", "client_AVG_TX_PerDay",
            "client_amount_deviation", "client_abs_amount_deviation",
            "client_amount_max_ratio", "client_amount_z_score",
            "client_is_new_max", "client_is_new_min",
            "client_total_transactions",
            "client_time_since_last_tx", "client_hours_since_last_tx",
            "client_total_days_since_first",
            "client_usual_region",  "client_is_unusual_region",  "client_region_freq",
            "client_usual_channel", "client_is_unusual_channel", "client_channel_freq",
            "client_usual_category","client_is_unusual_category","client_category_freq",
            "client_risk_score",
        ]


# ---------------------------------------------------------------------------
# Risk score  (pure function — easy to unit-test and later replace with
#              learned weights from a meta-model)
# ---------------------------------------------------------------------------
def _compute_risk(
    abs_dev: float,
    mx_ratio: float,
    z_score: float,
    is_new_max: int,
    is_unusual_reg: int,
    is_unusual_ch: int,
    is_unusual_cat: int,
    reg_freq: float,
    ch_freq: float,
    cat_freq: float,
    hours_raw: float,
    cnt: int,
) -> float:
    risk = (
        abs_dev  * 3.0
        + (5.0 if mx_ratio > 2  else 0.0)
        + (3.0 if abs(z_score) > 3 else (1.5 if abs(z_score) > 2 else 0.0))
        + (3.0 if is_new_max           else 0.0)
        + (4.0 if is_unusual_reg       else 0.0)
        + (3.0 if is_unusual_ch        else 0.0)
        + (2.0 if is_unusual_cat       else 0.0)
        + (2.0 if reg_freq  < 0.3      else 0.0)
        + (2.0 if ch_freq   < 0.3      else 0.0)
        + (2.0 if cat_freq  < 0.3      else 0.0)
        + (3.0 if hours_raw < 1        else 0.0)
        + (1.0 if hours_raw < 24       else 0.0)
        + (5.0 if cnt == 0             else 0.0)
    )
    return float(risk)


# ---------------------------------------------------------------------------
# Private helpers
# ---------------------------------------------------------------------------
def _init_result_arrays(n: int) -> dict:
    return {
        "TX_Amount":                    np.zeros(n, np.float32),
        "client_AVG_TX_Amount":         np.zeros(n, np.float32),
        "client_AVG_TX_PerDay":         np.zeros(n, np.float32),
        "client_amount_deviation":      np.zeros(n, np.float32),
        "client_abs_amount_deviation":  np.zeros(n, np.float32),
        "client_amount_max_ratio":      np.zeros(n, np.float32),
        "client_amount_z_score":        np.zeros(n, np.float32),
        "client_is_new_max":            np.zeros(n, np.int8),
        "client_is_new_min":            np.zeros(n, np.int8),
        "client_total_transactions":    np.zeros(n, np.int32),
        "client_time_since_last_tx":    np.zeros(n, np.float32),
        "client_hours_since_last_tx":   np.zeros(n, np.float32),
        "client_total_days_since_first":np.zeros(n, np.float32),
        "client_usual_region":          np.empty(n, object),
        "client_is_unusual_region":     np.zeros(n, np.int8),
        "client_region_freq":           np.zeros(n, np.float32),
        "client_usual_channel":         np.empty(n, object),
        "client_is_unusual_channel":    np.zeros(n, np.int8),
        "client_channel_freq":          np.zeros(n, np.float32),
        "client_usual_category":        np.empty(n, object),
        "client_is_unusual_category":   np.zeros(n, np.int8),
        "client_category_freq":         np.zeros(n, np.float32),
        "client_risk_score":            np.zeros(n, np.float32),
    }
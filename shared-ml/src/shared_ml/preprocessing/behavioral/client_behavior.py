from __future__ import annotations

import copy
import logging
import os
import threading
from collections import Counter, deque

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin

import dask

from ml_pipeline.utils.postal_codes import get_resolver

logger = logging.getLogger(__name__)

_DEFAULT_TIME_GAP_SEC = 30 * 86_400
_REQUIRED_COLS = {
    "Account_Id",
    "TX_Time",
    "TX_Amount",
    "Customer_Postal_Code",
    "Channel",
    "category",
}
_TIME_DIFFS_CAP = 200


def _add_region(df: pd.DataFrame) -> pd.DataFrame:
    resolver = get_resolver()
    return resolver.enrich_dataframe(
        df,
        postal_col="Customer_Postal_Code",
        add_gov=False,
        add_deleg=False,
        add_region=True,
    )


def _empty_state(max_time_diffs: int = _TIME_DIFFS_CAP) -> dict:
    """Initialise un état vide pour un compte."""
    return {
        "wf_count": 0,
        "wf_mean": 0.0,
        "wf_M2": 0.0,
        "cumcount": 0,
        "cumsum": 0.0,
        "max_amount": 0.0,
        "min_amount": np.inf,
        "last_time_ns": None,          # nanosecondes (int)
        "first_time_ns": None,          # nanosecondes (int)
        "gap_deque": deque(maxlen=max_time_diffs),   # stocke les gaps récents en secondes
        "gap_sum": 0.0,                  # somme des gaps dans la deque
        "gap_sum_sq": 0.0,                # somme des carrés des gaps
        "region_counter": Counter(),
        "channel_counter": Counter(),
        "category_counter": Counter(),
    }


# ---------------------------------------------------------------------------
# Vectorized per-account feature computation (optimisée)
# ---------------------------------------------------------------------------

def _compute_account_features_vectorized(
    amounts: np.ndarray,       # float64, shape (N,)
    times_ns: np.ndarray,      # int64 nanoseconds, shape (N,)
    regions: np.ndarray,       # object, shape (N,)
    channels: np.ndarray,      # object, shape (N,)
    categories: np.ndarray,    # object, shape (N,)
    init_state: dict,
    smoothing_days: float,
    default_gap_sec: float,
    max_time_diffs: int,
) -> dict:
    """
    Calcule les features comportementales pour un compte.
    Version optimisée avec deque pour les gaps, stockage des timestamps en ns,
    et calcul inline des modes/fréquences.
    """
    N = len(amounts)

    # ---- Output arrays ----
    out_avg            = np.zeros(N, dtype=np.float32)
    out_avg_per_day    = np.zeros(N, dtype=np.float32)
    out_dev            = np.zeros(N, dtype=np.float32)
    out_abs_dev        = np.zeros(N, dtype=np.float32)
    out_max_ratio      = np.zeros(N, dtype=np.float32)
    out_z_score        = np.zeros(N, dtype=np.float32)
    out_is_new_max     = np.zeros(N, dtype=np.int8)
    out_is_new_min     = np.zeros(N, dtype=np.int8)
    out_cnt            = np.zeros(N, dtype=np.int32)
    out_raw_gap        = np.zeros(N, dtype=np.float32)
    out_norm_gap       = np.zeros(N, dtype=np.float32)
    out_total_days     = np.zeros(N, dtype=np.float32)
    out_usual_reg      = np.empty(N, dtype=object)
    out_is_unu_reg     = np.zeros(N, dtype=np.int8)
    out_reg_freq       = np.zeros(N, dtype=np.float32)
    out_usual_ch       = np.empty(N, dtype=object)
    out_is_unu_ch      = np.zeros(N, dtype=np.int8)
    out_ch_freq        = np.zeros(N, dtype=np.float32)
    out_usual_cat      = np.empty(N, dtype=object)
    out_is_unu_cat     = np.zeros(N, dtype=np.int8)
    out_cat_freq       = np.zeros(N, dtype=np.float32)
    out_risk           = np.zeros(N, dtype=np.float32)

    # ---- Seed from initial state ----
    cnt      = init_state["cumcount"]
    cumsum   = init_state["cumsum"]
    wf_n     = init_state["wf_count"]
    wf_mean  = init_state["wf_mean"]
    wf_M2    = init_state["wf_M2"]
    cur_max  = init_state["max_amount"]
    cur_min  = init_state["min_amount"]
    last_ns  = init_state["last_time_ns"]   # déjà en ns ou None
    first_ns = init_state["first_time_ns"]   # déjà en ns ou None

    # Récupération de la deque et des sommes pour les gaps
    gap_deque   = init_state["gap_deque"]         # deque de float (secondes)
    gap_sum     = init_state["gap_sum"]
    gap_sum_sq  = init_state["gap_sum_sq"]

    # Compteurs pour les modes
    reg_ctr  = init_state["region_counter"]
    ch_ctr   = init_state["channel_counter"]
    cat_ctr  = init_state["category_counter"]

    NS_TO_SEC  = 1e-9
    NS_TO_DAY  = 1e-9 / 86_400

    for i in range(N):
        a   = float(amounts[i])
        t   = int(times_ns[i])
        reg = regions[i]
        ch  = channels[i]
        cat = categories[i]

        # ---- As-of stats (avant mise à jour de l'état) ----
        avg = cumsum / cnt if cnt > 0 else 0.0

        dev     = (a - avg) / (avg + 1e-6) if cnt > 0 else 0.0
        abs_dev = abs(dev)
        mx_ratio = a / (cur_max + 1e-6) if cur_max > 0 else 1.0
        is_new_max = int(cnt > 0 and a > cur_max)
        mn = cur_min if cur_min != np.inf else a
        is_new_min = int(cnt > 0 and a < mn)

        # Welford z-score
        wf_std = float(np.sqrt(wf_M2 / wf_n)) if wf_n >= 2 else 0.0
        z = (a - wf_mean) / (wf_std + 1e-6) if wf_n > 0 else 0.0

        # ---- Time gap (calcul optimisé avec deque) ----
        if last_ns is not None:
            raw_gap = (t - last_ns) * NS_TO_SEC
            if gap_deque:
                n = len(gap_deque)
                mean_gap = gap_sum / n
                # variance (population)
                var_gap = (gap_sum_sq / n) - (mean_gap * mean_gap)
                std_gap = np.sqrt(var_gap) if var_gap > 0 else mean_gap
                norm_gap = (raw_gap - mean_gap) / (std_gap + 1e-6)
                norm_gap = float(np.clip(norm_gap, -3, 3))
            else:
                norm_gap = 0.0
        else:
            raw_gap  = default_gap_sec
            norm_gap = 0.0

        hours_raw   = raw_gap / 3600.0
        total_days  = (t - first_ns) * NS_TO_DAY if first_ns is not None else 0.0
        avg_per_day = cnt / (total_days + smoothing_days)

        # ---- Modes / fréquences (inline) ----
        # Région
        if reg_ctr:
            mode_reg, count_reg = reg_ctr.most_common(1)[0]
            total_reg = sum(reg_ctr.values())
            usual_reg = mode_reg
            reg_freq = count_reg / total_reg
        else:
            usual_reg = "Unknown"
            reg_freq = 0.0
        is_unu_reg = int(cnt > 0 and reg != usual_reg)

        # Canal
        if ch_ctr:
            mode_ch, count_ch = ch_ctr.most_common(1)[0]
            total_ch = sum(ch_ctr.values())
            usual_ch = mode_ch
            ch_freq = count_ch / total_ch
        else:
            usual_ch = "Unknown"
            ch_freq = 0.0
        is_unu_ch = int(cnt > 0 and ch != usual_ch)

        # Catégorie
        if cat_ctr:
            mode_cat, count_cat = cat_ctr.most_common(1)[0]
            total_cat = sum(cat_ctr.values())
            usual_cat = mode_cat
            cat_freq = count_cat / total_cat
        else:
            usual_cat = "Unknown"
            cat_freq = 0.0
        is_unu_cat = int(cnt > 0 and cat != usual_cat)

        # Risque
        risk = (
            abs_dev * 3.0
            + (5.0 if mx_ratio > 2 else 0.0)
            + (3.0 if abs(z) > 3 else (1.5 if abs(z) > 2 else 0.0))
            + (3.0 if is_new_max else 0.0)
            + (4.0 if is_unu_reg else 0.0)
            + (3.0 if is_unu_ch else 0.0)
            + (2.0 if is_unu_cat else 0.0)
            + (2.0 if reg_freq < 0.3 else 0.0)
            + (2.0 if ch_freq < 0.3 else 0.0)
            + (2.0 if cat_freq < 0.3 else 0.0)
            + (3.0 if hours_raw < 1 else 0.0)
            + (1.0 if hours_raw < 24 else 0.0)
            + (5.0 if cnt == 0 else 0.0)
        )

        # ---- Écriture des résultats ----
        out_avg[i]         = avg
        out_avg_per_day[i] = avg_per_day
        out_dev[i]         = dev
        out_abs_dev[i]     = abs_dev
        out_max_ratio[i]   = mx_ratio
        out_z_score[i]     = z
        out_is_new_max[i]  = is_new_max
        out_is_new_min[i]  = is_new_min
        out_cnt[i]         = cnt
        out_raw_gap[i]     = raw_gap
        out_norm_gap[i]    = norm_gap
        out_total_days[i]  = total_days
        out_usual_reg[i]   = usual_reg
        out_is_unu_reg[i]  = is_unu_reg
        out_reg_freq[i]    = reg_freq
        out_usual_ch[i]    = usual_ch
        out_is_unu_ch[i]   = is_unu_ch
        out_ch_freq[i]     = ch_freq
        out_usual_cat[i]   = usual_cat
        out_is_unu_cat[i]  = is_unu_cat
        out_cat_freq[i]    = cat_freq
        out_risk[i]        = risk

        # ---- Mise à jour de l'état (après la transaction) ----
        if last_ns is not None:
            # Ajout du gap dans la deque et mise à jour des sommes
            if len(gap_deque) == gap_deque.maxlen:
                oldest = gap_deque[0]
                gap_sum -= oldest
                gap_sum_sq -= oldest * oldest
            gap_deque.append(raw_gap)
            gap_sum += raw_gap
            gap_sum_sq += raw_gap * raw_gap

        # Welford update
        wf_n   += 1
        delta   = a - wf_mean
        wf_mean += delta / wf_n
        wf_M2  += delta * (a - wf_mean)

        cnt     += 1
        cumsum  += a
        cur_max  = max(cur_max, a)
        if a < cur_min:
            cur_min = a
        last_ns  = t
        if first_ns is None:
            first_ns = t

        # Mise à jour des compteurs de catégories
        reg_ctr[reg] += 1
        ch_ctr[ch]   += 1
        cat_ctr[cat] += 1

    return {
        "client_AVG_TX_Amount":      out_avg,
        "client_AVG_TX_PerDay":      out_avg_per_day,
        "client_amount_deviation":   out_dev,
        "client_abs_amount_deviation": out_abs_dev,
        "client_amount_max_ratio":   out_max_ratio,
        "client_amount_z_score":     out_z_score,
        "client_is_new_max":         out_is_new_max,
        "client_is_new_min":         out_is_new_min,
        "client_total_transactions": out_cnt,
        "client_time_since_last_tx": out_raw_gap,
        "client_hours_since_last_tx": out_norm_gap,
        "client_total_days_since_first": out_total_days,
        "client_usual_region":       out_usual_reg,
        "client_is_unusual_region":  out_is_unu_reg,
        "client_region_freq":        out_reg_freq,
        "client_usual_channel":      out_usual_ch,
        "client_is_unusual_channel": out_is_unu_ch,
        "client_channel_freq":       out_ch_freq,
        "client_usual_category":     out_usual_cat,
        "client_is_unusual_category": out_is_unu_cat,
        "client_category_freq":      out_cat_freq,
        "client_risk_score":         out_risk,
    }


# ---------------------------------------------------------------------------
# Partition worker — called by Dask, processes a slice of accounts
# ---------------------------------------------------------------------------

def _process_partition(
    part_df: pd.DataFrame,
    partition_state: dict,      # only states for accounts in this partition
    smoothing_days: float,
    default_gap_sec: float,
    max_time_diffs: int,
) -> pd.DataFrame:
    result_frames = []

    for account_id, grp in part_df.groupby("Account_Id", sort=False):
        grp = grp.sort_values("TX_Time")

        amounts    = grp["TX_Amount"].to_numpy(dtype=np.float64)
        # Les timestamps sont déjà des datetime, on les convertit en ns
        times_ns   = grp["TX_Time"].astype(np.int64).to_numpy()
        regions    = grp["region"].to_numpy(dtype=object)
        channels   = grp["Channel"].to_numpy(dtype=object)
        categories = grp["category"].to_numpy(dtype=object)

        init_state = partition_state.get(account_id, _empty_state(max_time_diffs))

        feats = _compute_account_features_vectorized(
            amounts, times_ns, regions, channels, categories,
            init_state, smoothing_days, default_gap_sec, max_time_diffs,
        )

        frame = pd.DataFrame(feats, index=grp.index)
        frame.insert(0, "TX_Amount", amounts.astype(np.float32))
        frame.insert(0, "TX_Time",   grp["TX_Time"].to_numpy())
        result_frames.append(frame)

    if not result_frames:
        return pd.DataFrame()
    return pd.concat(result_frames)


# ---------------------------------------------------------------------------
# Partition worker for fit – computes final state for a group of accounts
# (adapté pour utiliser les nouvelles structures)
# ---------------------------------------------------------------------------

def _fit_partition(part_df: pd.DataFrame, max_time_diffs: int) -> dict:
    """Process a partition of accounts and return their final state dictionaries."""
    stats = {}
    for account_id, grp in part_df.groupby("Account_Id", sort=False):
        state = _empty_state(max_time_diffs)
        for tx in grp.itertuples(index=False):
            a = float(tx.TX_Amount)
            t = tx.TX_Time
            t_ns = t.value  # nanosecondes

            if state["last_time_ns"] is not None:
                gap = (t_ns - state["last_time_ns"]) * 1e-9  # secondes
                # Mise à jour de la deque et des sommes
                deque = state["gap_deque"]
                if len(deque) == deque.maxlen:
                    oldest = deque[0]
                    state["gap_sum"] -= oldest
                    state["gap_sum_sq"] -= oldest * oldest
                deque.append(gap)
                state["gap_sum"] += gap
                state["gap_sum_sq"] += gap * gap

            # Welford update
            wf_n = state["wf_count"] + 1
            delta = a - state["wf_mean"]
            new_mean = state["wf_mean"] + delta / wf_n
            state["wf_M2"] += delta * (a - new_mean)
            state["wf_count"] = wf_n
            state["wf_mean"] = new_mean

            state["cumcount"] += 1
            state["cumsum"] += a
            if a > state["max_amount"]:
                state["max_amount"] = a
            if a < state["min_amount"]:
                state["min_amount"] = a
            state["last_time_ns"] = t_ns
            if state["first_time_ns"] is None:
                state["first_time_ns"] = t_ns

            state["region_counter"][tx.region] += 1
            state["channel_counter"][tx.Channel] += 1
            state["category_counter"][tx.category] += 1

        stats[account_id] = state
    return stats


# ---------------------------------------------------------------------------
# Transformer (inchangé, sauf l'utilisation de max_time_diffs dans _empty_state)
# ---------------------------------------------------------------------------

class ClientBehaviorTransformer(BaseEstimator, TransformerMixin):
    def __init__(
        self,
        smoothing_days: int = 1,
        default_time_gap_days: int = 30,
        strict_as_of: bool = True,
        max_time_diffs: int = _TIME_DIFFS_CAP,
        n_partitions: int | None = None,
    ):
        self.smoothing_days = smoothing_days
        self.default_time_gap_days = default_time_gap_days
        self.strict_as_of = strict_as_of
        self.max_time_diffs = max_time_diffs
        self.n_partitions = n_partitions

        self._default_gap_sec = default_time_gap_days * 86_400
        self.client_stats_: dict = {}
        self._fit_lock = threading.Lock()

    @staticmethod
    def _validate(X: pd.DataFrame, context: str = ""):
        missing = _REQUIRED_COLS - set(X.columns)
        if missing:
            raise ValueError(
                f"ClientBehaviorTransformer.{context}: missing required columns: {sorted(missing)}"
            )
        if not pd.api.types.is_numeric_dtype(X["TX_Amount"]):
            raise TypeError(
                f"ClientBehaviorTransformer.{context}: TX_Amount must be numeric, "
                f"got {X['TX_Amount'].dtype}"
            )

    def fit(self, X: pd.DataFrame, y=None) -> "ClientBehaviorTransformer":
        self._validate(X, "fit")
        logger.info("ClientBehaviorTransformer.fit — %d rows", len(X))

        # Region enrichment happens here — once
        Xw = X.copy()
        Xw["TX_Time"] = pd.to_datetime(Xw["TX_Time"])
        if "region" not in Xw.columns:
            Xw = _add_region(Xw)
        Xw["Channel"] = Xw["Channel"].fillna("Unknown")
        Xw["category"] = Xw["category"].fillna("Unknown")
        Xw = Xw.sort_values(["Account_Id", "TX_Time"])

        n_clients = Xw["Account_Id"].nunique()
        # Ajustement du nombre de partitions
        n_partitions = self.n_partitions or max(
            1, min(n_clients // 500, os.cpu_count() * 4)
        )
        logger.info("fit: using %d Dask partitions", n_partitions)

        # Deterministic account → partition assignment by hash
        unique_accounts = Xw["Account_Id"].unique()
        account_to_part = {acc: hash(acc) % n_partitions for acc in unique_accounts}
        Xw["_partition"] = Xw["Account_Id"].map(account_to_part)

        delayed_results = []
        for _, part_df in Xw.groupby("_partition", sort=False):
            part_df = part_df.drop(columns=["_partition"])
            delayed_results.append(
                dask.delayed(_fit_partition)(part_df, self.max_time_diffs)
            )

        computed = dask.compute(*delayed_results, scheduler="processes")

        # Merge all stats
        stats = {}
        for part_stats in computed:
            stats.update(part_stats)

        with self._fit_lock:
            self.client_stats_ = stats

        logger.info(
            "ClientBehaviorTransformer.fit — history built for %d clients",
            len(self.client_stats_),
        )
        return self

    def transform_train_asof(self, X: pd.DataFrame) -> pd.DataFrame:
        return self._transform_core(X, initial_state={})

    def transform(
        self,
        X: pd.DataFrame,
        use_fitted_history: bool | None = None,
    ) -> pd.DataFrame:
        if use_fitted_history is None:
            use_fitted_history = not self.strict_as_of
        initial_state = copy.deepcopy(self.client_stats_) if use_fitted_history else {}
        return self._transform_core(X, initial_state=initial_state)

    def _transform_core(self, X: pd.DataFrame, initial_state: dict) -> pd.DataFrame:
        self._validate(X, "_transform_core")
        logger.info("ClientBehaviorTransformer._transform_core — %d rows", len(X))

        Xw = X.copy()
        Xw["TX_Time"] = pd.to_datetime(Xw["TX_Time"])

        if "region" not in Xw.columns:
            Xw = _add_region(Xw)

        Xw["Channel"] = Xw["Channel"].fillna("Unknown")
        Xw["category"] = Xw["category"].fillna("Unknown")

        n_clients = Xw["Account_Id"].nunique()
        n_partitions = self.n_partitions or max(
            1, min(n_clients // 500, os.cpu_count() * 4)
        )
        logger.info(
            "transform: using %d Dask partitions across %d CPUs",
            n_partitions, os.cpu_count()
        )

        unique_accounts = Xw["Account_Id"].unique()
        account_to_part = {acc: hash(acc) % n_partitions for acc in unique_accounts}
        Xw["_partition"] = Xw["Account_Id"].map(account_to_part)

        delayed_results = []
        for _, part_df in Xw.groupby("_partition", sort=False):
            part_df = part_df.drop(columns=["_partition"])
            part_accounts = set(part_df["Account_Id"].unique())
            partition_state = {
                acc: initial_state[acc]
                for acc in part_accounts
                if acc in initial_state
            }
            delayed_results.append(
                dask.delayed(_process_partition)(
                    part_df,
                    partition_state,
                    self.smoothing_days,
                    self._default_gap_sec,
                    self.max_time_diffs,
                )
            )

        computed = dask.compute(*delayed_results, scheduler="processes")

        result_df = pd.concat(computed).sort_index()
        logger.info(
            "ClientBehaviorTransformer._transform_core — done, shape=%s",
            result_df.shape,
        )
        return result_df

    def __getstate__(self):
        state = self.__dict__.copy()
        state.pop("_fit_lock", None)
        return state

    def __setstate__(self, state):
        self.__dict__.update(state)
        self._fit_lock = threading.Lock()

    def get_feature_names_out(self, input_features=None) -> list[str]:
        return [
            "TX_Time",
            "TX_Amount",
            "client_AVG_TX_Amount",
            "client_AVG_TX_PerDay",
            "client_amount_deviation",
            "client_abs_amount_deviation",
            "client_amount_max_ratio",
            "client_amount_z_score",
            "client_is_new_max",
            "client_is_new_min",
            "client_total_transactions",
            "client_time_since_last_tx",
            "client_hours_since_last_tx",
            "client_total_days_since_first",
            "client_usual_region",
            "client_is_unusual_region",
            "client_region_freq",
            "client_usual_channel",
            "client_is_unusual_channel",
            "client_channel_freq",
            "client_usual_category",
            "client_is_unusual_category",
            "client_category_freq",
            "client_risk_score",
        ]
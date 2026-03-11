from __future__ import annotations

import copy
import logging
import threading
from collections import Counter
from typing import Optional

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin

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


def _welford_update(count: int, mean: float, m2: float, value: float):
    count += 1
    delta = value - mean
    mean += delta / count
    m2 += delta * (value - mean)
    return count, mean, m2


def _welford_std(count: int, m2: float) -> float:
    if count < 2:
        return 0.0
    return float(np.sqrt(m2 / count))


def _counter_mode_freq(counter: Counter) -> tuple[str, float]:
    if not counter:
        return "Unknown", 0.0
    total = sum(counter.values())
    mode, cnt = counter.most_common(1)[0]
    return mode, cnt / total


def _empty_state() -> dict:
    return {
        "wf_count": 0,
        "wf_mean": 0.0,
        "wf_M2": 0.0,
        "cumcount": 0,
        "cumsum": 0.0,
        "max_amount": 0.0,
        "min_amount": np.inf,
        "last_time": None,
        "first_time": None,
        "time_diffs": [],
        "region_counter": Counter(),
        "channel_counter": Counter(),
        "category_counter": Counter(),
    }


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
        abs_dev * 3.0
        + (5.0 if mx_ratio > 2 else 0.0)
        + (3.0 if abs(z_score) > 3 else (1.5 if abs(z_score) > 2 else 0.0))
        + (3.0 if is_new_max else 0.0)
        + (4.0 if is_unusual_reg else 0.0)
        + (3.0 if is_unusual_ch else 0.0)
        + (2.0 if is_unusual_cat else 0.0)
        + (2.0 if reg_freq < 0.3 else 0.0)
        + (2.0 if ch_freq < 0.3 else 0.0)
        + (2.0 if cat_freq < 0.3 else 0.0)
        + (3.0 if hours_raw < 1 else 0.0)
        + (1.0 if hours_raw < 24 else 0.0)
        + (5.0 if cnt == 0 else 0.0)
    )
    return float(risk)


def _init_result_arrays(n: int) -> dict:
    return {
        "TX_Time": np.empty(n, dtype=object),
        "TX_Amount": np.zeros(n, dtype=np.float32),
        "client_AVG_TX_Amount": np.zeros(n, dtype=np.float32),
        "client_AVG_TX_PerDay": np.zeros(n, dtype=np.float32),
        "client_amount_deviation": np.zeros(n, dtype=np.float32),
        "client_abs_amount_deviation": np.zeros(n, dtype=np.float32),
        "client_amount_max_ratio": np.zeros(n, dtype=np.float32),
        "client_amount_z_score": np.zeros(n, dtype=np.float32),
        "client_is_new_max": np.zeros(n, dtype=np.int8),
        "client_is_new_min": np.zeros(n, dtype=np.int8),
        "client_total_transactions": np.zeros(n, dtype=np.int32),
        "client_time_since_last_tx": np.zeros(n, dtype=np.float32),
        "client_hours_since_last_tx": np.zeros(n, dtype=np.float32),
        "client_total_days_since_first": np.zeros(n, dtype=np.float32),
        "client_usual_region": np.empty(n, dtype=object),
        "client_is_unusual_region": np.zeros(n, dtype=np.int8),
        "client_region_freq": np.zeros(n, dtype=np.float32),
        "client_usual_channel": np.empty(n, dtype=object),
        "client_is_unusual_channel": np.zeros(n, dtype=np.int8),
        "client_channel_freq": np.zeros(n, dtype=np.float32),
        "client_usual_category": np.empty(n, dtype=object),
        "client_is_unusual_category": np.zeros(n, dtype=np.int8),
        "client_category_freq": np.zeros(n, dtype=np.float32),
        "client_risk_score": np.zeros(n, dtype=np.float32),
    }


class ClientBehaviorTransformer(BaseEstimator, TransformerMixin):
    def __init__(
        self,
        smoothing_days: int = 1,
        default_time_gap_days: int = 30,
        strict_as_of: bool = True,
        max_time_diffs: int = _TIME_DIFFS_CAP,
    ):
        self.smoothing_days = smoothing_days
        self.default_time_gap_days = default_time_gap_days
        self.strict_as_of = strict_as_of
        self.max_time_diffs = max_time_diffs

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
                f"ClientBehaviorTransformer.{context}: TX_Amount must be numeric, got {X['TX_Amount'].dtype}"
            )

    def fit(self, X: pd.DataFrame, y=None) -> "ClientBehaviorTransformer":
        self._validate(X, "fit")
        logger.info("ClientBehaviorTransformer.fit — %d rows", len(X))

        Xw = X.copy()
        Xw["TX_Time"] = pd.to_datetime(Xw["TX_Time"])
        Xw = _add_region(Xw)
        Xw["Channel"] = Xw["Channel"].fillna("Unknown")
        Xw["category"] = Xw["category"].fillna("Unknown")
        Xw = Xw.sort_values(["Account_Id", "TX_Time"])

        stats: dict = {}

        for account_id, grp in Xw.groupby("Account_Id", sort=False):
            state = _empty_state()

            for _, tx in grp.iterrows():
                cur_amount = float(tx["TX_Amount"])
                cur_time = tx["TX_Time"]
                cur_region = tx["region"]
                cur_channel = tx["Channel"]
                cur_category = tx["category"]

                if state["last_time"] is not None:
                    gap = (cur_time - state["last_time"]).total_seconds()
                    state["time_diffs"].append(gap)
                    if len(state["time_diffs"]) > self.max_time_diffs:
                        state["time_diffs"] = state["time_diffs"][-self.max_time_diffs:]

                state["wf_count"], state["wf_mean"], state["wf_M2"] = _welford_update(
                    state["wf_count"], state["wf_mean"], state["wf_M2"], cur_amount
                )
                state["cumcount"] += 1
                state["cumsum"] += cur_amount
                state["max_amount"] = max(state["max_amount"], cur_amount)
                if cur_amount < state["min_amount"]:
                    state["min_amount"] = cur_amount
                state["last_time"] = cur_time
                if state["first_time"] is None:
                    state["first_time"] = cur_time

                state["region_counter"][cur_region] += 1
                state["channel_counter"][cur_channel] += 1
                state["category_counter"][cur_category] += 1

            stats[account_id] = state

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
        Xw = _add_region(Xw)
        Xw["Channel"] = Xw["Channel"].fillna("Unknown")
        Xw["category"] = Xw["category"].fillna("Unknown")
        Xw["_orig_idx"] = np.arange(len(Xw))

        X_sorted = Xw.sort_values(["Account_Id", "TX_Time", "_orig_idx"]).reset_index(drop=True)
        res = _init_result_arrays(len(X_sorted))
        current_state = copy.deepcopy(initial_state)

        for account_id, grp in X_sorted.groupby("Account_Id", sort=False):
            if account_id not in current_state:
                current_state[account_id] = _empty_state()

            state = current_state[account_id]

            for _, tx in grp.iterrows():
                cur_time = tx["TX_Time"]
                cur_amount = float(tx["TX_Amount"])
                cur_region = tx["region"]
                cur_channel = tx["Channel"]
                cur_category = tx["category"]

                cnt = state["cumcount"]
                csum = state["cumsum"]
                avg = csum / cnt if cnt > 0 else 0.0
                mx = state["max_amount"]
                mn = state["min_amount"] if state["min_amount"] != np.inf else cur_amount

                dev = (cur_amount - avg) / (avg + 1e-6) if cnt > 0 else 0.0
                abs_dev = abs(dev)
                mx_ratio = cur_amount / (mx + 1e-6) if mx > 0 else 1.0
                is_new_max = int(cnt > 0 and cur_amount > mx)
                is_new_min = int(cnt > 0 and cur_amount < mn)

                wf_std = _welford_std(state["wf_count"], state["wf_M2"])
                z_score = (
                    (cur_amount - state["wf_mean"]) / (wf_std + 1e-6)
                    if state["wf_count"] > 0
                    else 0.0
                )

                if state["last_time"] is not None:
                    raw_gap_sec = (cur_time - pd.Timestamp(state["last_time"])).total_seconds()
                    td = state["time_diffs"]
                    if td:
                        avg_td = float(np.mean(td))
                        std_td = float(np.std(td)) if len(td) > 1 else avg_td
                        norm_gap = float(np.clip((raw_gap_sec - avg_td) / (std_td + 1e-6), -3, 3))
                    else:
                        norm_gap = 0.0
                else:
                    raw_gap_sec = float(self._default_gap_sec)
                    norm_gap = 0.0

                hours_raw = raw_gap_sec / 3600.0
                total_days = (
                    (cur_time - pd.Timestamp(state["first_time"])).total_seconds() / 86_400
                    if state["first_time"] is not None
                    else 0.0
                )
                avg_per_day = cnt / (total_days + self.smoothing_days)

                usual_reg, reg_freq = _counter_mode_freq(state["region_counter"])
                usual_ch, ch_freq = _counter_mode_freq(state["channel_counter"])
                usual_cat, cat_freq = _counter_mode_freq(state["category_counter"])

                is_unusual_reg = int(cnt > 0 and cur_region != usual_reg)
                is_unusual_ch = int(cnt > 0 and cur_channel != usual_ch)
                is_unusual_cat = int(cnt > 0 and cur_category != usual_cat)

                risk = _compute_risk(
                    abs_dev,
                    mx_ratio,
                    z_score,
                    is_new_max,
                    is_unusual_reg,
                    is_unusual_ch,
                    is_unusual_cat,
                    reg_freq,
                    ch_freq,
                    cat_freq,
                    hours_raw,
                    cnt,
                )

                orig = int(tx["_orig_idx"])
                res["TX_Time"][orig] = cur_time
                res["TX_Amount"][orig] = cur_amount
                res["client_AVG_TX_Amount"][orig] = avg
                res["client_AVG_TX_PerDay"][orig] = avg_per_day
                res["client_amount_deviation"][orig] = dev
                res["client_abs_amount_deviation"][orig] = abs_dev
                res["client_amount_max_ratio"][orig] = mx_ratio
                res["client_amount_z_score"][orig] = z_score
                res["client_is_new_max"][orig] = is_new_max
                res["client_is_new_min"][orig] = is_new_min
                res["client_total_transactions"][orig] = cnt
                res["client_time_since_last_tx"][orig] = raw_gap_sec
                res["client_hours_since_last_tx"][orig] = norm_gap
                res["client_total_days_since_first"][orig] = total_days
                res["client_usual_region"][orig] = usual_reg
                res["client_is_unusual_region"][orig] = is_unusual_reg
                res["client_region_freq"][orig] = reg_freq
                res["client_usual_channel"][orig] = usual_ch
                res["client_is_unusual_channel"][orig] = is_unusual_ch
                res["client_channel_freq"][orig] = ch_freq
                res["client_usual_category"][orig] = usual_cat
                res["client_is_unusual_category"][orig] = is_unusual_cat
                res["client_category_freq"][orig] = cat_freq
                res["client_risk_score"][orig] = risk

                if state["last_time"] is not None:
                    gap = (cur_time - pd.Timestamp(state["last_time"])).total_seconds()
                    state["time_diffs"].append(gap)
                    if len(state["time_diffs"]) > self.max_time_diffs:
                        state["time_diffs"] = state["time_diffs"][-self.max_time_diffs:]

                state["wf_count"], state["wf_mean"], state["wf_M2"] = _welford_update(
                    state["wf_count"], state["wf_mean"], state["wf_M2"], cur_amount
                )
                state["cumcount"] += 1
                state["cumsum"] += cur_amount
                state["max_amount"] = max(state["max_amount"], cur_amount)
                if cur_amount < state["min_amount"]:
                    state["min_amount"] = cur_amount
                state["last_time"] = cur_time
                if state["first_time"] is None:
                    state["first_time"] = cur_time

                state["region_counter"][cur_region] += 1
                state["channel_counter"][cur_channel] += 1
                state["category_counter"][cur_category] += 1

        result_df = pd.DataFrame(res)
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
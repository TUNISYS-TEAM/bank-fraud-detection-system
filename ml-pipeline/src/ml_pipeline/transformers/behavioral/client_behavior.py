from __future__ import annotations

import copy
import logging
import threading
from collections import Counter
from typing import Optional

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin

# Dask imports
import dask.dataframe as dd
from dask.diagnostics import ProgressBar
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


def _process_account_partition(partition_df: pd.DataFrame, initial_state_dict: dict, transformer_params: dict) -> pd.DataFrame:
    """
    Process a single partition of accounts (multiple accounts per partition).
    This function is called by Dask on each partition.
    """
    # Extract transformer parameters
    smoothing_days = transformer_params.get('smoothing_days', 1)
    default_gap_sec = transformer_params.get('default_gap_sec', 30 * 86_400)
    max_time_diffs = transformer_params.get('max_time_diffs', 200)
    
    # Ensure required columns
    required = {"Account_Id", "TX_Time", "TX_Amount", "region", "Channel", "category"}
    missing = required - set(partition_df.columns)
    if missing:
        raise ValueError(f"Partition missing required columns: {missing}")
    
    # Sort by Account_Id and TX_Time within the partition
    partition_df = partition_df.sort_values(["Account_Id", "TX_Time"]).reset_index(drop=True)
    
    # Initialize result arrays for this partition
    res = _init_result_arrays(len(partition_df))
    
    # Process each account in the partition sequentially
    current_account = None
    state = None
    start_idx = 0
    
    for idx, row in partition_df.iterrows():
        account_id = row["Account_Id"]
        
        # New account encountered
        if account_id != current_account:
            # Save previous account's final state if needed
            current_account = account_id
            # Get initial state for this account from fitted history
            if account_id in initial_state_dict:
                state = copy.deepcopy(initial_state_dict[account_id])
            else:
                state = _empty_state()
            start_idx = idx
        
        # Process current transaction
        cur_time = row["TX_Time"]
        cur_amount = float(row["TX_Amount"])
        cur_region = row["region"]
        cur_channel = row["Channel"]
        cur_category = row["category"]
        
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
            raw_gap_sec = float(default_gap_sec)
            norm_gap = 0.0
        
        hours_raw = raw_gap_sec / 3600.0
        total_days = (
            (cur_time - pd.Timestamp(state["first_time"])).total_seconds() / 86_400
            if state["first_time"] is not None
            else 0.0
        )
        avg_per_day = cnt / (total_days + smoothing_days)
        
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
        
        # Fill result arrays
        res["TX_Time"][idx] = cur_time
        res["TX_Amount"][idx] = cur_amount
        res["client_AVG_TX_Amount"][idx] = avg
        res["client_AVG_TX_PerDay"][idx] = avg_per_day
        res["client_amount_deviation"][idx] = dev
        res["client_abs_amount_deviation"][idx] = abs_dev
        res["client_amount_max_ratio"][idx] = mx_ratio
        res["client_amount_z_score"][idx] = z_score
        res["client_is_new_max"][idx] = is_new_max
        res["client_is_new_min"][idx] = is_new_min
        res["client_total_transactions"][idx] = cnt
        res["client_time_since_last_tx"][idx] = raw_gap_sec
        res["client_hours_since_last_tx"][idx] = norm_gap
        res["client_total_days_since_first"][idx] = total_days
        res["client_usual_region"][idx] = usual_reg
        res["client_is_unusual_region"][idx] = is_unusual_reg
        res["client_region_freq"][idx] = reg_freq
        res["client_usual_channel"][idx] = usual_ch
        res["client_is_unusual_channel"][idx] = is_unusual_ch
        res["client_channel_freq"][idx] = ch_freq
        res["client_usual_category"][idx] = usual_cat
        res["client_is_unusual_category"][idx] = is_unusual_cat
        res["client_category_freq"][idx] = cat_freq
        res["client_risk_score"][idx] = risk
        
        # Update state
        if state["last_time"] is not None:
            gap = (cur_time - pd.Timestamp(state["last_time"])).total_seconds()
            state["time_diffs"].append(gap)
            if len(state["time_diffs"]) > max_time_diffs:
                state["time_diffs"] = state["time_diffs"][-max_time_diffs:]
        
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
    
    return pd.DataFrame(res)


class ClientBehaviorTransformer(BaseEstimator, TransformerMixin):
    def __init__(
        self,
        smoothing_days: int = 1,
        default_time_gap_days: int = 30,
        strict_as_of: bool = True,
        max_time_diffs: int = _TIME_DIFFS_CAP,
        n_partitions: int | None = None,  # Number of Dask partitions
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

        # Prepare data
        Xw = X.copy()
        Xw["TX_Time"] = pd.to_datetime(Xw["TX_Time"])
        Xw = _add_region(Xw)
        Xw["Channel"] = Xw["Channel"].fillna("Unknown")
        Xw["category"] = Xw["category"].fillna("Unknown")
        
        # Determine number of partitions
        n_partitions = self.n_partitions
        if n_partitions is None:
            # Auto-determine based on data size and client count
            n_clients = Xw["Account_Id"].nunique()
            n_rows = len(Xw)
            # Aim for ~1000 clients per partition, but at least 1 partition
            n_partitions = max(1, min(n_clients // 1000, 100))
            # Also cap by row count (max 100k rows per partition)
            n_partitions = max(n_partitions, n_rows // 100000)
            n_partitions = min(n_partitions, 200)  # Upper limit
        
        logger.info(f"Using {n_partitions} Dask partitions")
        
        # Convert to Dask DataFrame
        ddf = dd.from_pandas(Xw, npartitions=n_partitions)
        
        # Prepare transformer parameters for worker functions
        transformer_params = {
            'smoothing_days': self.smoothing_days,
            'default_gap_sec': self._default_gap_sec,
            'max_time_diffs': self.max_time_diffs
        }
        
        # Apply transformation in parallel
        with ProgressBar():
            # Map partitions to processing function
            result_ddf = ddf.map_partitions(
                _process_account_partition,
                initial_state_dict=initial_state,
                transformer_params=transformer_params,
                meta={
                    "TX_Time": "object",
                    "TX_Amount": "float32",
                    "client_AVG_TX_Amount": "float32",
                    "client_AVG_TX_PerDay": "float32",
                    "client_amount_deviation": "float32",
                    "client_abs_amount_deviation": "float32",
                    "client_amount_max_ratio": "float32",
                    "client_amount_z_score": "float32",
                    "client_is_new_max": "int8",
                    "client_is_new_min": "int8",
                    "client_total_transactions": "int32",
                    "client_time_since_last_tx": "float32",
                    "client_hours_since_last_tx": "float32",
                    "client_total_days_since_first": "float32",
                    "client_usual_region": "object",
                    "client_is_unusual_region": "int8",
                    "client_region_freq": "float32",
                    "client_usual_channel": "object",
                    "client_is_unusual_channel": "int8",
                    "client_channel_freq": "float32",
                    "client_usual_category": "object",
                    "client_is_unusual_category": "int8",
                    "client_category_freq": "float32",
                    "client_risk_score": "float32",
                }
            )
            
            # Compute result
            result_df = result_ddf.compute()
        
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
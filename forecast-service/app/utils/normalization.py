def min_max_scale(value: float, min_val: float, max_val: float) -> float:
    """Normalisation Min-Max entre 0 et 1."""
    if max_val == min_val:
        return 0.5
    scaled = (value - min_val) / (max_val - min_val)
    return max(0.0, min(1.0, scaled))
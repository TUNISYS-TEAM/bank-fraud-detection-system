import joblib
from pathlib import Path

def atomic_save(obj, path: Path) -> None:
    temp_path = path.with_suffix(path.suffix + ".tmp")
    joblib.dump(obj, temp_path)
    temp_path.rename(path)

def atomic_load(path: Path):
    return joblib.load(path)
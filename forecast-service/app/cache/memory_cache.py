from __future__ import annotations

import time
from threading import RLock
from typing import Any, Dict, Optional


class MemoryCache:
    """
    Cache mémoire avec TTL et métriques de performance.
    """

    def __init__(self, default_ttl_seconds: int = 300):
        self.default_ttl_seconds = default_ttl_seconds
        self._data: Dict[str, Dict[str, Any]] = {}
        self._lock = RLock()
        self._hits = 0
        self._misses = 0

    @property
    def hit_rate(self) -> float:
        total = self._hits + self._misses
        return self._hits / total if total > 0 else 0.0

    @property
    def size(self) -> int:
        return len(self._data)

    def get(self, key: str) -> Optional[Any]:
        now = time.time()

        with self._lock:
            entry = self._data.get(key)

            if entry is None:
                self._misses += 1
                return None

            if entry["expires_at"] < now:
                self._data.pop(key, None)
                self._misses += 1
                return None

            self._hits += 1
            return entry["value"]

    def set(self, key: str, value: Any, ttl_seconds: Optional[int] = None) -> None:
        ttl = ttl_seconds or self.default_ttl_seconds

        with self._lock:
            self._data[key] = {
                "value": value,
                "expires_at": time.time() + ttl,
            }

    def clear(self) -> None:
        with self._lock:
            self._data.clear()

    def delete(self, key: str) -> None:
        with self._lock:
            self._data.pop(key, None)

    def replace_many(self, new_data: Dict[str, Any]) -> None:
        """Remplace atomiquement tout le contenu du cache."""
        now = time.time()
        with self._lock:
            self._data = {
                key: {"value": value, "expires_at": now + self.default_ttl_seconds}
                for key, value in new_data.items()
            }

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "size": self.size,
                "hits": self._hits,
                "misses": self._misses,
                "hit_rate": round(self.hit_rate, 4),
            }


cache = MemoryCache()
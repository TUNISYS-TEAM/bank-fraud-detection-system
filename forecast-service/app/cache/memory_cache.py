from __future__ import annotations

import time
from threading import RLock
from typing import Any, Dict, Optional


class MemoryCache:
    """
    Cache mémoire simple avec TTL.

    Ce cache est suffisant pour une V1 locale du service.
    En production avancée, il pourra être remplacé par Redis.
    """

    def __init__(self, default_ttl_seconds: int = 300):
        self.default_ttl_seconds = default_ttl_seconds
        self._data: Dict[str, Dict[str, Any]] = {}
        self._lock = RLock()

    def get(self, key: str) -> Optional[Any]:
        now = time.time()

        with self._lock:
            entry = self._data.get(key)

            if entry is None:
                return None

            if entry["expires_at"] < now:
                self._data.pop(key, None)
                return None

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


cache = MemoryCache()
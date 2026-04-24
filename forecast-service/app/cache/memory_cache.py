from typing import Dict, Optional, Any

class MemoryCache:
    """Cache mémoire simple sous forme de dictionnaire."""
    def __init__(self):
        self._data: Dict[str, Any] = {}  # clé = "75001_7"

    def get(self, key: str) -> Optional[Any]:
        return self._data.get(key)

    def set(self, key: str, value: Any) -> None:
        self._data[key] = value

    def clear(self) -> None:
        self._data.clear()

# Instance globale partagée
cache = MemoryCache()
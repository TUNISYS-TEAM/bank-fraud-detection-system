from datetime import datetime, timedelta

def get_future_window(days: int) -> tuple:
    """
    Retourne la date de début et de fin d'une fenêtre future de `days` jours
    à partir d'aujourd'hui.
    """
    start = datetime.now()
    end = start + timedelta(days=days)
    return start.isoformat(), end.isoformat()
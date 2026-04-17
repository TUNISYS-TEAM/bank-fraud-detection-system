import pytest
from fastapi.testclient import TestClient
from unittest.mock import MagicMock, patch

from app.main import app
from app.models.bundles import HybridEnsembleBundle

client = TestClient(app)

@pytest.fixture
def mock_hybrid_bundle():
    """Crée un mock de bundle hybride pour les tests."""
    bundle = MagicMock(spec=HybridEnsembleBundle)
    bundle.threshold = 0.5
    return bundle

def test_health_check():
    """Vérifie que le service est en vie."""
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "healthy"

@patch("app.models.model_registry.registry.get_model")
@patch("app.inference.hybrid_engine.HybridEngine.predict")
def test_predict_success(mock_predict, mock_get_model, mock_hybrid_bundle):
    """Teste une prédiction unitaire réussie avec mock."""
    # Configuration du mock
    mock_get_model.return_value = {"model": mock_hybrid_bundle, "preprocessor": None}
    mock_predict.return_value = {
        "fraud_score": 0.85,
        "risk_level": "HIGH",
        "alert_type": "HYBRID_ENSEMBLE",
        "supervised_score": 0.8,
        "anomaly_score": 0.9,
        "fraud_reason": {"reason": "Test"},
        "behavioral_signals": {"signal": "Test"}
    }

    payload = {
        "model_uuid": "test-uuid-123",
        "storage_path": "path/to/model",
        "transaction": {"TX_Amount": 100.0, "Terminal_ID": "T001"},
        "has_existing_behavior": False
    }

    response = client.post("/predict", json=payload)
    
    assert response.status_code == 200
    data = response.json()
    assert data["fraud_score"] == 0.85
    assert data["risk_level"] == "HIGH"
    mock_get_model.assert_called_once()

@patch("app.models.model_registry.registry.get_model")
@patch("app.inference.hybrid_engine.HybridEngine.predict")
def test_predict_batch_success(mock_predict, mock_get_model, mock_hybrid_bundle):
    """Teste une prédiction par lot (batch)."""
    mock_get_model.return_value = {"model": mock_hybrid_bundle, "preprocessor": None}
    mock_predict.return_value = {
        "fraud_score": 0.1,
        "risk_level": "LOW",
        "alert_type": "HYBRID_ENSEMBLE",
        "supervised_score": 0.1,
        "anomaly_score": 0.1,
        "fraud_reason": {},
        "behavioral_signals": {}
    }

    payload = {
        "model_uuid": "test-uuid-batch",
        "batch": [
            {"transaction": {"TX_Amount": 10.0}},
            {"transaction": {"TX_Amount": 20.0}}
        ]
    }

    response = client.post("/predict-batch", json=payload)
    
    assert response.status_code == 200
    results = response.json()["results"]
    assert len(results) == 2
    assert results[0]["fraud_score"] == 0.1
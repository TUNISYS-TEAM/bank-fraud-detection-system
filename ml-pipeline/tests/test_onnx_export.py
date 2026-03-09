import pytest
import numpy as np
import pandas as pd
from pathlib import Path

# Skip ONNX tests if required packages not installed
pytest.importorskip("onnx")
pytest.importorskip("onnxruntime")
pytest.importorskip("skl2onnx")

from ml_pipeline.models.supervised.lightgbm_model import LightGBMFraudModel
from ml_pipeline.pipeline.onnx_exporter import ONNXExporter
from ml_pipeline.utils.onnx_utils import verify_onnx_equivalence


@pytest.fixture
def sample_data():
    X = np.random.randn(50, 5).astype(np.float32)
    y = np.random.choice([0, 1], 50, p=[0.8, 0.2])
    X_val = np.random.randn(20, 5).astype(np.float32)
    y_val = np.random.choice([0, 1], 20, p=[0.8, 0.2])
    return X, y, X_val, y_val


@pytest.fixture
def trained_lightgbm(sample_data):
    X, y, X_val, y_val = sample_data
    model = LightGBMFraudModel(
        n_estimators=5,
        max_depth=2,
        early_stopping_rounds=2,
        calibrate=False,
    )
    model.fit(X, y, X_val, y_val)
    return model


class TestONNXExport:
    def test_export_lightgbm(self, tmp_path, trained_lightgbm):
        exporter = ONNXExporter(
            model_type="lightgbm",
            model_path=None,  # we'll pass model directly? In exporter we load from path, so need to save first.
            # Instead, we can test the conversion function directly.
        )
        # But our ONNXExporter expects a path. We'll save the model first.
        model_path = tmp_path / "model.joblib"
        trained_lightgbm.save(model_path)
        exporter = ONNXExporter(
            model_type="lightgbm",
            model_path=model_path,
            output_dir=tmp_path,
        )
        onnx_path = exporter.export(verify=False)  # skip verify because we need test data
        assert onnx_path.exists()
        assert onnx_path.with_suffix(".meta.json").exists()

    def test_verify_equivalence(self, tmp_path, trained_lightgbm, sample_data):
        X, _, _, _ = sample_data
        model_path = tmp_path / "model.joblib"
        trained_lightgbm.save(model_path)
        exporter = ONNXExporter(
            model_type="lightgbm",
            model_path=model_path,
            output_dir=tmp_path,
        )
        onnx_path = exporter.export(verify=False)
        # Generate test data
        test_data = np.random.randn(10, 5).astype(np.float32)
        # Verify equivalence
        result = verify_onnx_equivalence(
            original_model=trained_lightgbm,
            onnx_path=onnx_path,
            test_data=test_data,
            tolerance=1e-4,
        )
        assert result is True

    @pytest.mark.skip(reason="ECOD and COPOD cannot export to ONNX")
    def test_unsupported_export(self):
        pass
import numpy as np
import pytest

pytest.importorskip("onnx")
pytest.importorskip("onnxruntime")
pytest.importorskip("skl2onnx")

from app.models.supervised.lightgbm_model import LightGBMFraudModel
from app.pipeline.onnx_exporter import ONNXExporter
from app.utils.onnx_utils import verify_onnx_equivalence


@pytest.fixture
def sample_data():
    rng = np.random.default_rng(42)
    X = rng.normal(size=(50, 5)).astype(np.float32)
    y = rng.choice([0, 1], size=50, p=[0.8, 0.2])
    X_val = rng.normal(size=(20, 5)).astype(np.float32)
    y_val = rng.choice([0, 1], size=20, p=[0.8, 0.2])
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
        model_path = tmp_path / "model.joblib"
        trained_lightgbm.save(model_path)

        exporter = ONNXExporter(
            model_type="lightgbm",
            model_path=model_path,
            output_dir=tmp_path,
        )
        onnx_path = exporter.export(verify=False)

        assert onnx_path.exists()
        assert onnx_path.with_suffix(".meta.json").exists()

    def test_verify_equivalence(self, tmp_path, trained_lightgbm):
        rng = np.random.default_rng(123)
        test_data = rng.normal(size=(10, 5)).astype(np.float32)

        model_path = tmp_path / "model.joblib"
        trained_lightgbm.save(model_path)

        exporter = ONNXExporter(
            model_type="lightgbm",
            model_path=model_path,
            output_dir=tmp_path,
        )
        onnx_path = exporter.export(verify=False)

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
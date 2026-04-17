-- Migration pour ajouter le moteur d'inférence aux modèles ML
ALTER TABLE model_ml ADD COLUMN inference_engine VARCHAR(30) DEFAULT 'ONNX_LOCAL';

-- S'assurer que les modèles existants ont une valeur par défaut
UPDATE model_ml SET inference_engine = 'ONNX_LOCAL' WHERE inference_engine IS NULL;
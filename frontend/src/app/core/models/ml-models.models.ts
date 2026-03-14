export interface Dataset {
  datasetUuid: string;
  datasetName: string;
  datasetCode: string;
  fileSizeMb: number;
  rowCount: number;
  status: 'ACTIVE' | 'PROCESSED' | 'INACTIVE';
  importedAt: string;
  filePath?: string;
  featureColumns?: any;
  statistics?: {
    rowCount: number;
    columnCount: number;
    fraudRate: number;
    duplicateCount: number;
    duplicateRate: number;
    totalMissingValues: number;
    memoryUsageMb: number;
  };
}

export interface FeatureInfo {
  name: string;
  enabled: boolean;
  type: string;
  description: string;
  category: 'behavioral' | 'temporal' | 'transaction' | 'categorical';
  importance?: number;
}

export interface ModelConfig {
  modelName: string;
  modelType: string;
  hyperparameters: {
    n_estimators: number;
    max_depth: number;
    min_samples_split: number;
    min_samples_leaf: number;
    max_features: string;
    bootstrap: boolean;
    class_weight?: string;
    [key: string]: any;
  };
  testSize: number;
  randomSeed: number;
}

export interface TrainingJobRequest {
  datasetUuid: string;
  jobName: string;
  hyperparameters?: Record<string, any>;
  testSize: number;
  randomSeed: number;
  enableHyperparameterTuning?: boolean;
}

export interface TrainingJobResponse {
  jobUuid: string;
  jobName: string;
  status: string;
  datasetUuid: string;
  modelUuid: string;
  hyperparameters?: Record<string, any>;
  metrics?: {
    accuracy?: number;
    precision: number;
    recall: number;
    f1_score: number;
    auc_roc?: number;
  };
  // Add these top-level properties
  f1Score?: number;
  precision?: number;
  recall?: number;
  aucRoc?: number;
  fraudRate?: number;
  featureImportance?: Record<string, number>;
  confusionMatrix?: {
    true_negatives: number;
    false_positives: number;
    false_negatives: number;
    true_positives: number;
  };
  trainingRows?: number;
  testRows?: number;
  modelPath?: string;
  startedAt?: string;
  completedAt?: string;
  durationSeconds?: number;
}

export interface ModelMetricsResponse {
  modelUuid: string;
  modelName: string;
  modelType: string;
  version: string;
  status: string;
  accuracy?: number;
  precision?: number;
  recall?: number;
  f1Score?: number;
  aucRoc?: number;
  confusionMatrix?: {
    trueNegatives: number;
    falsePositives: number;
    falseNegatives: number;
    truePositives: number;
  };
  featureImportance?: Record<string, number>;
  topFeatures?: string[];
  onnxPath?: string;
}
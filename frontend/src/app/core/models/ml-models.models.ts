export interface Dataset {
  datasetUuid: string;
  datasetName: string;
  datasetCode: string;
  fileSizeMb: number;
  rowCount: number;
  status: 'ACTIVE' | 'PROCESSED' | 'INACTIVE' | 'CLEANED' | null;
  visibility: boolean;
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
    learning_rate?: number;
    subsample?: number;
    num_leaves?: number;
    min_child_samples?: number;
    colsample_bytree?: number;
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
  isTest?: boolean;
}

export interface TrainingJobResponse {
  jobUuid: string;
  jobName: string;
  status: string;
  datasetUuid: string;
  modelUuid: string;
  modelName?: string;
  modelType?: string;
  version?: string;
  hyperparameters?: Record<string, any>;
  metrics?: {
    accuracy?: number;
    precision: number;
    recall: number;
    f1_score: number;
    auc_roc?: number;
  };
  // Top-level properties for easy access
  f1Score?: number;
  precision?: number;
  recall?: number;
  aucRoc?: number;
  fraudRate?: number;
  featureImportance?: Record<string, number>;
  selectedFeatures?: string[];  // Add this
  confusionMatrix?: {
    true_negatives: number;
    false_positives: number;
    false_negatives: number;
    true_positives: number;
  };
  trainingRows?: number;
  testRows?: number;
  thresholds?: Record<string, any>;
  modelPath?: string;
  pipelinePath?: string;
  onnxPath?: string;
  startedAt?: string;
  completedAt?: string;
  durationSeconds?: number;
  errorMessage?: string;
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
  averagePrecision?: number;
  confusionMatrix?: {
    trueNegatives: number;
    falsePositives: number;
    falseNegatives: number;
    truePositives: number;
  };
  trueNegatives?: number;
  falsePositives?: number;
  falseNegatives?: number;
  truePositives?: number;
  specificity?: number;
  falsePositiveRate?: number;
  falseNegativeRate?: number;
  prevalence?: number;
  featureImportance?: Record<string, number>;
  topFeatures?: string[];
  selectedFeatures?: string[];  
  onnxPath?: string;
  thresholds?: Record<string, any>;
  hyperparameters?: Record<string, any>;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}
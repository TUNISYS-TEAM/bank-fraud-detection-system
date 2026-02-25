CREATE TABLE dataset (
    id BIGSERIAL PRIMARY KEY,
    dataset_uuid UUID DEFAULT uuid_generate_v4() UNIQUE,

    dataset_code VARCHAR(50) UNIQUE NOT NULL, 
    dataset_name VARCHAR(255) NOT NULL,

    file_path VARCHAR(500),
    file_size_mb DECIMAL(10,2),               -- Taille en MB
    record_count INTEGER,                     -- Nb lignes

    status VARCHAR(20) NOT NULL CHECK (
        status IN ('ACTIVE', 'INACTIVE')
    ),

    created_by_id BIGINT REFERENCES app_user(id),
    imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dataset_status ON dataset(status);
CREATE INDEX idx_dataset_imported_at ON dataset(imported_at);
# Requirement File Upload Support

# --- !Ups

-- File attachments table for storing uploaded file metadata
CREATE TABLE requirement_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (uploaded_by) REFERENCES users(id),
    
    INDEX idx_filename (filename),
    INDEX idx_uploaded_by (uploaded_by),
    INDEX idx_created_at (created_at)
);

-- Junction table linking files to requirements within specific risk assessments
CREATE TABLE risk_assessment_requirement_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    risk_assessment_id BIGINT NOT NULL,
    requirement_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (risk_assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES requirement_files(id) ON DELETE CASCADE,
    FOREIGN KEY (uploaded_by) REFERENCES users(id),
    
    INDEX idx_risk_assessment_id (risk_assessment_id),
    INDEX idx_requirement_id (requirement_id),
    INDEX idx_file_id (file_id),
    INDEX idx_uploaded_by (uploaded_by),
    UNIQUE KEY unique_assessment_req_file (risk_assessment_id, requirement_id, file_id)
);

# --- !Downs

DROP TABLE IF EXISTS risk_assessment_requirement_files;
DROP TABLE IF EXISTS requirement_files;
-- Add model_type enum to models table

ALTER TABLE models 
ADD COLUMN model_type VARCHAR(20) NOT NULL DEFAULT 'CHAT';

-- update any existing text-embedding based models
UPDATE models 
SET model_type = 'EMBEDDING' 
WHERE model_name LIKE '%embed%' OR name LIKE '%embed%';

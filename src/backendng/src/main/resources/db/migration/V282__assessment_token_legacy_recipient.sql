-- Older installations retain respondent_email alongside the current email column.
-- AssessmentToken writes email; retaining a NOT NULL legacy column prevents new
-- respondent links from being saved. Keep historical values without requiring
-- new tokens to populate the obsolete column. Fresh schemas may lack it entirely.
ALTER TABLE assessment_token
    MODIFY COLUMN IF EXISTS respondent_email VARCHAR(255) NULL;

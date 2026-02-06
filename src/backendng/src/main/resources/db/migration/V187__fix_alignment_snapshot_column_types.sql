-- Fix alignment_snapshot column types: details/previous_details need LONGTEXT for large requirement content
ALTER TABLE alignment_snapshot MODIFY COLUMN details LONGTEXT;
ALTER TABLE alignment_snapshot MODIFY COLUMN previous_details LONGTEXT;
ALTER TABLE alignment_snapshot MODIFY COLUMN chapter VARCHAR(500);
ALTER TABLE alignment_snapshot MODIFY COLUMN previous_chapter VARCHAR(500);

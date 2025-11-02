-- Migration: Fix incorrectly assigned email provider types
-- Feature: Amazon SES Email Integration - Data Fix
-- Date: 2025-11-02
-- Description: Corrects provider type for existing email configurations based on actual configuration data
-- This migration fixes cases where SMTP configs were incorrectly labeled as AMAZON_SES or vice versa

-- Fix SMTP configurations that are incorrectly marked as AMAZON_SES
-- If smtp_host is set and ses_region is NULL, it's an SMTP configuration
UPDATE email_configs
SET provider = 'SMTP'
WHERE smtp_host IS NOT NULL
  AND ses_region IS NULL
  AND provider = 'AMAZON_SES';

-- Fix Amazon SES configurations that are incorrectly marked as SMTP
-- If ses_region is set and smtp_host is NULL, it's an Amazon SES configuration
UPDATE email_configs
SET provider = 'AMAZON_SES'
WHERE ses_region IS NOT NULL
  AND smtp_host IS NULL
  AND provider = 'SMTP';

-- Log the fix for audit purposes
-- SELECT
--   id,
--   name,
--   provider,
--   smtp_host,
--   ses_region,
--   'Fixed provider type based on configuration data' AS note
-- FROM email_configs;

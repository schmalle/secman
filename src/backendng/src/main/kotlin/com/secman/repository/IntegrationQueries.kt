package com.secman.repository

/** Closed query templates: callers supply values only, never query fragments. */
enum class IntegrationPageQuery(val select: String, val count: String) {
    SCANNERS("SELECT x FROM IntegrationScanner x WHERE (:unscoped = true OR EXISTS (SELECT s.id FROM IntegrationSubject s WHERE s.scannerId = x.id AND s.assetId IN :assets)) ORDER BY x.id DESC",
        "SELECT COUNT(x) FROM IntegrationScanner x WHERE (:unscoped = true OR EXISTS (SELECT s.id FROM IntegrationSubject s WHERE s.scannerId = x.id AND s.assetId IN :assets))"),
    SUBJECTS("SELECT x FROM IntegrationSubject x WHERE x.scannerId = :scanner AND x.assetId IN :assets ORDER BY x.id DESC",
        "SELECT COUNT(x) FROM IntegrationSubject x WHERE x.scannerId = :scanner AND x.assetId IN :assets"),
    RUNS("SELECT x FROM IntegrationRun x, IntegrationSubject s WHERE x.subjectId = s.id AND s.assetId IN :assets AND (:scanner IS NULL OR x.scannerId = :scanner) AND (:subject IS NULL OR x.subjectId = :subject) ORDER BY x.id DESC",
        "SELECT COUNT(x) FROM IntegrationRun x, IntegrationSubject s WHERE x.subjectId = s.id AND s.assetId IN :assets AND (:scanner IS NULL OR x.scannerId = :scanner) AND (:subject IS NULL OR x.subjectId = :subject)"),
    FINDINGS("SELECT x FROM IntegrationFinding x, IntegrationSubject s, IntegrationScanner c, Asset a WHERE x.subjectId = s.id AND s.scannerId = c.id AND s.assetId = a.id AND s.assetId IN :assets AND (:scanner IS NULL OR s.scannerId = :scanner) AND (:subject IS NULL OR s.id = :subject) AND (:repo IS NULL OR s.githubRepositoryId = :repo) AND (:source IS NULL OR c.source = :source) AND (:owner IS NULL OR a.owner = :owner) AND (:severity IS NULL OR x.severity = :severity) AND (:state IS NULL OR x.state = :state) AND (:search IS NULL OR LOCATE(:search, LOWER(x.title)) > 0 OR LOCATE(:search, LOWER(x.externalId)) > 0) ORDER BY x.id DESC",
        "SELECT COUNT(x) FROM IntegrationFinding x, IntegrationSubject s, IntegrationScanner c, Asset a WHERE x.subjectId = s.id AND s.scannerId = c.id AND s.assetId = a.id AND s.assetId IN :assets AND (:scanner IS NULL OR s.scannerId = :scanner) AND (:subject IS NULL OR s.id = :subject) AND (:repo IS NULL OR s.githubRepositoryId = :repo) AND (:source IS NULL OR c.source = :source) AND (:owner IS NULL OR a.owner = :owner) AND (:severity IS NULL OR x.severity = :severity) AND (:state IS NULL OR x.state = :state) AND (:search IS NULL OR LOCATE(:search, LOWER(x.title)) > 0 OR LOCATE(:search, LOWER(x.externalId)) > 0)");
}
enum class IntegrationCountQuery(val sql: String) {
    SCANNERS("SELECT COUNT(x) FROM IntegrationScanner x WHERE (:unscoped = true OR EXISTS (SELECT s.id FROM IntegrationSubject s WHERE s.scannerId = x.id AND s.assetId IN :assets))"),
    SUBJECTS("SELECT COUNT(x) FROM IntegrationSubject x, IntegrationScanner c WHERE x.scannerId = c.id AND (:unscoped = true OR x.assetId IN :assets)"),
    HEALTHY("SELECT COUNT(x) FROM IntegrationSubject x, IntegrationScanner c WHERE x.scannerId = c.id AND (:unscoped = true OR x.assetId IN :assets) AND x.lastStatus = 'SUCCESS' AND NOT (timestampadd(hour, c.staleAfterHours, coalesce(x.lastSuccessfulScanAt, x.createdAt)) < :now)"),
    FAILED("SELECT COUNT(x) FROM IntegrationSubject x, IntegrationScanner c WHERE x.scannerId = c.id AND (:unscoped = true OR x.assetId IN :assets) AND x.lastStatus IN ('FAILED', 'PARTIAL')"),
    UNSCANNED("SELECT COUNT(x) FROM IntegrationSubject x, IntegrationScanner c WHERE x.scannerId = c.id AND (:unscoped = true OR x.assetId IN :assets) AND x.lastScanAt IS NULL"),
    STALE("SELECT COUNT(x) FROM IntegrationSubject x, IntegrationScanner c WHERE x.scannerId = c.id AND (:unscoped = true OR x.assetId IN :assets) AND c.enabled = true AND timestampadd(hour, c.staleAfterHours, coalesce(x.lastSuccessfulScanAt, x.createdAt)) < :now"),
    FINDINGS("SELECT COUNT(x) FROM IntegrationFinding x, IntegrationSubject s WHERE x.subjectId = s.id AND x.state = 'OPEN' AND (:unscoped = true OR s.assetId IN :assets)");
}

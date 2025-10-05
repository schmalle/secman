"""T026-T028: FalconClient service with pagination and retry logic."""
import time
import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Optional

from falconpy import SpotlightVulnerabilities

from src.models.auth_context import AuthenticationContext
from src.models.device import Device
from src.models.enums import DeviceType, ExitCode, Severity
from src.models.filter_criteria import FilterCriteria
from src.models.vulnerability import Vulnerability
from src.models.vulnerability_record import VulnerabilityRecord

logger = logging.getLogger(__name__)


class FalconClient:
    """CrowdStrike Falcon API client for vulnerability queries."""

    def __init__(self, auth: AuthenticationContext) -> None:
        """Initialize FalconClient with authentication context.

        Args:
            auth: Authentication context with credentials and base URL

        Raises:
            SystemExit: Exit code 1 if authentication fails
        """
        self.auth = auth
        try:
            self.client = SpotlightVulnerabilities(
                client_id=auth.client_id,
                client_secret=auth.client_secret,
                base_url=auth.base_url,
                timeout=(10, 30)  # (connect, read) timeouts
            )
            logger.debug(f"Initialized Falcon client for region {auth.cloud_region}")
        except Exception as e:
            logger.error(f"Authentication failed: {e}")
            raise SystemExit(ExitCode.AUTH_ERROR)

    def query_vulnerabilities(
        self,
        criteria: FilterCriteria,
        progress_callback: Optional[Callable[[int, int, int], None]] = None
    ) -> list[VulnerabilityRecord]:
        """Query vulnerabilities with pagination.

        Args:
            criteria: Filter criteria for the query
            progress_callback: Optional callback for progress updates (page_num, total_pages, record_count)

        Returns:
            List of VulnerabilityRecord objects

        Raises:
            SystemExit: Various exit codes based on error type
        """
        # Build FQL filter
        fql_filter = self._build_fql_filter(criteria)
        logger.debug(f"FQL filter: {fql_filter}")

        # Query with pagination
        all_records: list[VulnerabilityRecord] = []
        offset = 0
        limit = 500  # Optimal page size
        total_records: Optional[int] = None
        page_num = 1

        while True:
            logger.debug(f"Fetching page {page_num} (offset={offset}, limit={limit})")

            # Make API call with retry logic
            response = self._query_with_retry(fql_filter, limit, offset)

            # Parse response
            resources = response.get("body", {}).get("resources", [])
            pagination = response.get("body", {}).get("meta", {}).get("pagination", {})
            total_records = pagination.get("total", 0)

            # Convert API response to VulnerabilityRecord objects
            for resource in resources:
                try:
                    record = self._parse_resource(resource, criteria)
                    all_records.append(record)
                except Exception as e:
                    logger.warning(f"Failed to parse resource: {e}")
                    continue

            # Progress callback
            if progress_callback:
                total_pages = (total_records + limit - 1) // limit if total_records else 0
                progress_callback(page_num, total_pages, len(all_records))

            # Check if we've retrieved all records
            if offset + limit >= total_records:
                break

            offset += limit
            page_num += 1

        logger.info(f"Retrieved {len(all_records)} vulnerability records")
        return all_records

    def _build_fql_filter(self, criteria: FilterCriteria) -> str:
        """Build FQL filter with min_days_open calculation.

        Args:
            criteria: Filter criteria

        Returns:
            FQL filter string
        """
        fql = criteria.to_fql()

        # Add min_days_open filter if specified
        if criteria.min_days_open > 0:
            cutoff_date = datetime.now(timezone.utc) - timedelta(days=criteria.min_days_open)
            timestamp_str = cutoff_date.strftime("%Y-%m-%dT%H:%M:%SZ")
            days_filter = f"created_timestamp:<'{timestamp_str}'"
            fql = f"{fql}+{days_filter}" if fql else days_filter

        return fql

    def _query_with_retry(self, fql_filter: str, limit: int, offset: int) -> dict[str, Any]:
        """Execute query with exponential backoff retry logic.

        Args:
            fql_filter: FQL filter string
            limit: Page size
            offset: Pagination offset

        Returns:
            API response dictionary

        Raises:
            SystemExit: Exit code 2 (network), 4 (API retry exhausted)
        """
        retries = 0
        max_retries = 5
        base_delay = 1.0  # seconds

        while retries <= max_retries:
            try:
                response: dict[str, Any] = self.client.queryVulnerabilitiesCombined(
                    filter=fql_filter,
                    limit=limit,
                    offset=offset
                )

                # Check response status
                status_code = response.get("status_code", 0)

                if status_code == 200:
                    return response
                elif status_code == 401:
                    logger.error("Authentication failed: Invalid credentials")
                    raise SystemExit(ExitCode.AUTH_ERROR)
                elif status_code == 429:
                    # Rate limit - retry with backoff
                    if retries >= max_retries:
                        logger.error("Rate limit retry exhausted")
                        raise SystemExit(ExitCode.API_ERROR)
                    delay = base_delay * (2 ** retries)
                    logger.warning(f"Rate limit hit, retrying in {delay}s (attempt {retries + 1}/{max_retries})")
                    time.sleep(delay)
                    retries += 1
                    continue
                elif status_code in [502, 503, 504]:
                    # Server errors - retry with backoff
                    if retries >= max_retries:
                        logger.error("Server error retry exhausted")
                        raise SystemExit(ExitCode.API_ERROR)
                    delay = base_delay * (2 ** retries)
                    logger.warning(f"Server error {status_code}, retrying in {delay}s (attempt {retries + 1}/{max_retries})")
                    time.sleep(delay)
                    retries += 1
                    continue
                else:
                    logger.error(f"API error: {status_code}")
                    raise SystemExit(ExitCode.API_ERROR)

            except Exception as e:
                if "timeout" in str(e).lower() or "connection" in str(e).lower():
                    if retries >= max_retries:
                        logger.error(f"Network error: {e}")
                        raise SystemExit(ExitCode.NETWORK_ERROR)
                    delay = base_delay * (2 ** retries)
                    logger.warning(f"Network timeout, retrying in {delay}s (attempt {retries + 1}/{max_retries})")
                    time.sleep(delay)
                    retries += 1
                    continue
                else:
                    logger.error(f"Unexpected error: {e}")
                    raise SystemExit(ExitCode.API_ERROR)

        # Should not reach here, but just in case
        logger.error("Max retries exceeded")
        raise SystemExit(ExitCode.API_ERROR)

    def _parse_resource(self, resource: dict[str, Any], criteria: FilterCriteria) -> VulnerabilityRecord:
        """Parse API resource into VulnerabilityRecord.

        Args:
            resource: API response resource object
            criteria: Filter criteria (used for device type inference)

        Returns:
            VulnerabilityRecord object
        """
        # Parse CVE data
        cve_data = resource.get("cve", {})
        cve_id = cve_data.get("id", "UNKNOWN")
        severity_str = cve_data.get("severity", "MEDIUM").upper()
        severity = Severity[severity_str] if severity_str in Severity.__members__ else Severity.MEDIUM
        cvss_score = cve_data.get("cvss_score")
        description = cve_data.get("description")

        # Parse product version
        apps_data = resource.get("apps", {})
        product_name_version = apps_data.get("product_name_version", "Unknown")
        product_versions = [product_name_version]

        # Calculate days open
        created_timestamp = resource.get("created_timestamp", "")
        try:
            detected_date = datetime.fromisoformat(created_timestamp.replace("Z", "+00:00"))
            days_open = (datetime.now(timezone.utc) - detected_date).days
        except Exception:
            detected_date = datetime.now(timezone.utc)
            days_open = 0

        vulnerability = Vulnerability(
            cve_id=cve_id,
            severity=severity,
            product_versions=product_versions,
            days_open=days_open,
            detected_date=detected_date,
            cvss_score=cvss_score,
            description=description
        )

        # Parse host data
        host_data = resource.get("host", {})
        device_id = resource.get("id", "UNKNOWN")
        hostname = host_data.get("hostname", "UNKNOWN")
        local_ip = host_data.get("local_ip", "0.0.0.0")
        host_groups = host_data.get("groups", [])
        cloud_account_id = host_data.get("cloud_provider_account_id")
        cloud_instance_id = host_data.get("instance_id")
        os_version = host_data.get("os_version", "Unknown")
        ad_domain = host_data.get("ad_domain")
        platform_name = host_data.get("platform_name", "Unknown")

        # Infer device type from platform_name
        if "Workstation" in platform_name or "Desktop" in platform_name:
            device_type = DeviceType.CLIENT
        elif "Server" in platform_name:
            device_type = DeviceType.SERVER
        else:
            device_type = criteria.device_type  # Use filter criteria as fallback

        device = Device(
            device_id=device_id,
            hostname=hostname,
            local_ip=local_ip,
            host_groups=host_groups,
            os_version=os_version,
            device_type=device_type,
            platform_name=platform_name,
            cloud_account_id=cloud_account_id,
            cloud_instance_id=cloud_instance_id,
            ad_domain=ad_domain
        )

        return VulnerabilityRecord(vulnerability=vulnerability, device=device)

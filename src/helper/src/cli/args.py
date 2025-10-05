"""T033: Argument parser for CLI."""
import argparse
from typing import Any

from src.models.enums import DeviceType, Severity, ExportFormat


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser(
        prog="falcon-vulns",
        description="Query CrowdStrike Falcon for device vulnerabilities with filtering and export.",
        epilog="For more information: https://www.falconpy.io"
    )

    # Required arguments
    parser.add_argument(
        "--device-type",
        type=str,
        required=True,
        choices=[dt.value for dt in DeviceType],
        help="Device type filter (CLIENT=workstations, SERVER=servers, BOTH=all)"
    )

    parser.add_argument(
        "--severity",
        type=str,
        nargs="+",
        required=True,
        choices=[s.value for s in Severity],
        help="Vulnerability severity levels (space-separated for multiple)"
    )

    parser.add_argument(
        "--min-days-open",
        type=int,
        required=True,
        help="Minimum days vulnerability has been open (0=all ages)"
    )

    # Optional arguments
    parser.add_argument(
        "--ad-domain",
        type=str,
        help="Filter by Active Directory domain"
    )

    parser.add_argument(
        "--hostname",
        type=str,
        help="Filter by specific hostname"
    )

    parser.add_argument(
        "--output",
        type=str,
        help="Custom export file path (default: auto-generated with timestamp)"
    )

    parser.add_argument(
        "--format",
        type=str,
        default="XLSX",
        choices=[fmt.value for fmt in ExportFormat],
        help="Export format (default: XLSX)"
    )

    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Enable detailed logging (DEBUG level)"
    )

    args = parser.parse_args(argv)

    # Validate min-days-open
    if args.min_days_open < 0:
        parser.error(f"--min-days-open must be >= 0, got {args.min_days_open}")

    return args

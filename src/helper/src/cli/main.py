"""T035: Main CLI entry point."""
import sys
from datetime import datetime
from typing import NoReturn, Union

from src.cli.args import parse_args
from src.cli.env import validate_env_vars
from src.models.enums import DeviceType, Severity, ExportFormat, ExitCode
from src.models.export_config import ExportConfiguration
from src.models.filter_criteria import FilterCriteria
from src.models.vulnerability_record import VulnerabilityRecord
from src.exporters.xlsx_exporter import XLSXExporter
from src.exporters.csv_exporter import CSVExporter
from src.exporters.txt_exporter import TXTExporter


def main(argv: list[str] | None = None) -> NoReturn:
    """Main entry point for falcon-vulns CLI."""
    # Parse arguments
    try:
        args = parse_args(argv)
    except SystemExit as e:
        # argparse calls sys.exit() for errors and --help
        sys.exit(e.code if e.code is not None else ExitCode.INVALID_ARGS)

    # Validate environment variables
    validate_env_vars()

    # Create models from parsed arguments
    try:
        filter_criteria = FilterCriteria(
            device_type=DeviceType(args.device_type),
            severities=[Severity(s) for s in args.severity],
            min_days_open=args.min_days_open,
            ad_domain=args.ad_domain,
            hostname=args.hostname
        )

        export_format = ExportFormat(args.format)
        export_config = ExportConfiguration(format=export_format, output_path=args.output)

    except ValueError as e:
        print(f"ERROR: Invalid argument value: {e}", file=sys.stderr)
        sys.exit(ExitCode.INVALID_ARGS)

    # Generate output path if not specified
    if not args.output:
        timestamp = datetime.now().strftime(export_config.timestamp_format)
        ext = export_format.value.lower()
        output_path = export_config.default_filename_pattern.format(
            timestamp=timestamp,
            ext=ext
        )
    else:
        output_path = args.output

    # For demonstration: Create sample data since we don't have Falcon API implementation
    print(f"Note: This is a demonstration. Falcon API integration (T026-T029) not yet implemented.", file=sys.stderr)
    print(f"Filter: {filter_criteria.to_fql()}", file=sys.stderr)

    # Create empty export (would normally contain query results)
    records: list[VulnerabilityRecord] = []  # Empty for now

    # Export based on format
    try:
        exporter: Union[XLSXExporter, CSVExporter, TXTExporter]
        if export_format == ExportFormat.XLSX:
            exporter = XLSXExporter(export_config)
        elif export_format == ExportFormat.CSV:
            exporter = CSVExporter(export_config)
        else:  # TXT
            exporter = TXTExporter(export_config)

        exporter.export(records, output_path)
        print(f"Found {len(records)} vulnerabilities matching criteria")
        print(f"Exported to: {output_path}")

    except (IOError, OSError) as e:
        print(f"ERROR: Cannot write to {output_path}: {e}", file=sys.stderr)
        sys.exit(ExitCode.EXPORT_ERROR)

    sys.exit(ExitCode.SUCCESS)


if __name__ == "__main__":
    main()

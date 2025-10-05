"""T031: CSV exporter using stdlib csv module."""
import csv
from typing import List

from src.models.export_config import ExportConfiguration
from src.models.vulnerability_record import VulnerabilityRecord


class CSVExporter:
    """Export vulnerability records to CSV format."""

    def __init__(self, config: ExportConfiguration) -> None:
        """Initialize CSV exporter."""
        self.config = config

    def export(self, records: List[VulnerabilityRecord], output_path: str) -> None:
        """Export records to CSV file."""
        with open(output_path, 'w', newline='', encoding='utf-8') as csvfile:
            writer = csv.writer(csvfile, quoting=csv.QUOTE_MINIMAL)

            # Write header row
            writer.writerow(self.config.column_order)

            # Write data rows
            for record in records:
                writer.writerow(record.to_row())

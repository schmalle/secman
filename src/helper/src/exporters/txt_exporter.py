"""T032: TXT exporter with tab-delimited format."""
from typing import List

from src.models.export_config import ExportConfiguration
from src.models.vulnerability_record import VulnerabilityRecord


class TXTExporter:
    """Export vulnerability records to tab-delimited text format."""

    def __init__(self, config: ExportConfiguration) -> None:
        """Initialize TXT exporter."""
        self.config = config

    def export(self, records: List[VulnerabilityRecord], output_path: str) -> None:
        """Export records to TXT file."""
        with open(output_path, 'w', encoding='utf-8') as txtfile:
            # Write header row
            txtfile.write("\t".join(self.config.column_order) + "\n")

            # Write data rows
            for record in records:
                txtfile.write("\t".join(record.to_row()) + "\n")

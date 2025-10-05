"""T038: File path utilities for export operations."""
import os
from datetime import datetime
from pathlib import Path

from src.lib.error_handling import ExportError
from src.models.enums import ExportFormat


def generate_default_filename(export_format: ExportFormat) -> str:
    """Generate default filename with timestamp.

    Args:
        export_format: Export format (XLSX, CSV, or TXT)

    Returns:
        Filename in format: falcon_vulns_YYYYMMDD_HHMMSS.ext
    """
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    extension = export_format.value.lower()
    return f"falcon_vulns_{timestamp}.{extension}"


def validate_output_path(output_path: str) -> str:
    """Validate that output path is writable.

    Args:
        output_path: Path to output file

    Returns:
        Absolute path to output file

    Raises:
        ExportError: If path is not writable (exit code 5)
    """
    # Convert to absolute path
    abs_path = os.path.abspath(output_path)

    # Check if directory exists and is writable
    parent_dir = os.path.dirname(abs_path)

    if not os.path.exists(parent_dir):
        raise ExportError(f"Directory does not exist: {parent_dir}")

    if not os.path.isdir(parent_dir):
        raise ExportError(f"Parent path is not a directory: {parent_dir}")

    if not os.access(parent_dir, os.W_OK):
        raise ExportError(f"Directory is not writable: {parent_dir}")

    # Check if file exists and is writable (if it exists)
    if os.path.exists(abs_path):
        if not os.path.isfile(abs_path):
            raise ExportError(f"Path exists but is not a file: {abs_path}")
        if not os.access(abs_path, os.W_OK):
            raise ExportError(f"File exists but is not writable: {abs_path}")

    return abs_path


def ensure_extension(output_path: str, export_format: ExportFormat) -> str:
    """Ensure output path has correct extension for format.

    Args:
        output_path: User-provided output path
        export_format: Export format

    Returns:
        Output path with correct extension
    """
    extension = export_format.value.lower()
    path = Path(output_path)

    # If no extension or wrong extension, add correct one
    if path.suffix.lower() != f".{extension}":
        return f"{output_path}.{extension}"

    return output_path

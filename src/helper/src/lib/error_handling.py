"""T037: Error handling utilities and custom exceptions."""
from src.models.enums import ExitCode


class VulnerabilityToolError(Exception):
    """Base exception for vulnerability tool errors."""

    def __init__(self, message: str, exit_code: ExitCode) -> None:
        """Initialize error with message and exit code.

        Args:
            message: Error message
            exit_code: Exit code to use when this error is raised
        """
        self.message = message
        self.exit_code = exit_code
        super().__init__(message)


class AuthError(VulnerabilityToolError):
    """Authentication error (exit code 1)."""

    def __init__(self, message: str = "Authentication failed") -> None:
        """Initialize authentication error.

        Args:
            message: Error message
        """
        super().__init__(message, ExitCode.AUTH_ERROR)


class NetworkError(VulnerabilityToolError):
    """Network error (exit code 2)."""

    def __init__(self, message: str = "Network error occurred") -> None:
        """Initialize network error.

        Args:
            message: Error message
        """
        super().__init__(message, ExitCode.NETWORK_ERROR)


class InvalidArgsError(VulnerabilityToolError):
    """Invalid arguments error (exit code 3)."""

    def __init__(self, message: str = "Invalid arguments provided") -> None:
        """Initialize invalid arguments error.

        Args:
            message: Error message
        """
        super().__init__(message, ExitCode.INVALID_ARGS)


class APIError(VulnerabilityToolError):
    """API error (exit code 4)."""

    def __init__(self, message: str = "API error occurred") -> None:
        """Initialize API error.

        Args:
            message: Error message
        """
        super().__init__(message, ExitCode.API_ERROR)


class ExportError(VulnerabilityToolError):
    """Export error (exit code 5)."""

    def __init__(self, message: str = "Export error occurred") -> None:
        """Initialize export error.

        Args:
            message: Error message
        """
        super().__init__(message, ExitCode.EXPORT_ERROR)

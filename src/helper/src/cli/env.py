"""T034: Environment variable validation."""
import os
import sys

from src.models.enums import ExitCode


def validate_env_vars() -> None:
    """Validate required environment variables are present."""
    required_vars = ["FALCON_CLIENT_ID", "FALCON_CLIENT_SECRET", "FALCON_CLOUD_REGION"]
    missing = []

    for var in required_vars:
        if not os.getenv(var):
            missing.append(var)

    if missing:
        print(f"ERROR: Missing required environment variable(s): {', '.join(missing)}", file=sys.stderr)
        print("Please set FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, and FALCON_CLOUD_REGION", file=sys.stderr)
        sys.exit(ExitCode.AUTH_ERROR)

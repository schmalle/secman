"""T024: AuthenticationContext data model."""
import os
from dataclasses import dataclass


@dataclass
class AuthenticationContext:
    """CrowdStrike Falcon API credentials and connection configuration."""

    client_id: str
    client_secret: str
    cloud_region: str
    base_url: str

    @classmethod
    def from_env(cls) -> "AuthenticationContext":
        """Load authentication context from environment variables."""
        client_id = os.getenv("FALCON_CLIENT_ID")
        client_secret = os.getenv("FALCON_CLIENT_SECRET")
        cloud_region = os.getenv("FALCON_CLOUD_REGION")

        if not client_id:
            raise EnvironmentError("Missing required environment variable: FALCON_CLIENT_ID")
        if not client_secret:
            raise EnvironmentError("Missing required environment variable: FALCON_CLIENT_SECRET")
        if not cloud_region:
            raise EnvironmentError("Missing required environment variable: FALCON_CLOUD_REGION")

        # Derive base URL from region
        base_url = cls._region_to_url(cloud_region)

        return cls(
            client_id=client_id,
            client_secret=client_secret,
            cloud_region=cloud_region,
            base_url=base_url
        )

    @staticmethod
    def _region_to_url(region: str) -> str:
        """Convert cloud region to base URL."""
        region_map = {
            "us-1": "https://api.crowdstrike.com",
            "us-2": "https://api.us-2.crowdstrike.com",
            "eu-1": "https://api.eu-1.crowdstrike.com",
            "us-gov-1": "https://api.laggar.gcw.crowdstrike.com",
        }
        return region_map.get(region, "https://api.crowdstrike.com")

    def __post_init__(self) -> None:
        """Validate authentication context."""
        if not self.client_id:
            raise ValueError("client_id cannot be empty")
        if not self.client_secret:
            raise ValueError("client_secret cannot be empty")
        if not self.cloud_region:
            raise ValueError("cloud_region cannot be empty")

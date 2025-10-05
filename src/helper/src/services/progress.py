"""T029: Progress indication service."""
import sys
import threading
import time
from typing import Optional


class ProgressIndicator:
    """Background progress indication for long-running queries."""

    def __init__(self, delay_seconds: float = 10.0) -> None:
        """Initialize progress indicator.

        Args:
            delay_seconds: Delay before starting progress updates (default 10s)
        """
        self.delay_seconds = delay_seconds
        self.current_page: int = 0
        self.total_pages: Optional[int] = None
        self.record_count: int = 0
        self._started = False
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._last_update_time = 0.0

    def start(self) -> None:
        """Start the progress indicator thread."""
        if self._started:
            return
        self._started = True
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Stop the progress indicator thread."""
        if not self._started:
            return
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=1.0)
        self._started = False

    def update(self, page_num: int, total_pages: int, record_count: int) -> None:
        """Update progress information.

        Args:
            page_num: Current page number
            total_pages: Total number of pages (or estimate)
            record_count: Total records retrieved so far
        """
        self.current_page = page_num
        self.total_pages = total_pages
        self.record_count = record_count

    def _run(self) -> None:
        """Background thread that prints progress updates."""
        # Wait for initial delay
        if self._stop_event.wait(self.delay_seconds):
            return  # Stopped before delay elapsed

        # Print progress updates
        while not self._stop_event.is_set():
            self._print_progress()
            # Update every 2 seconds after initial delay
            if self._stop_event.wait(2.0):
                break

    def _print_progress(self) -> None:
        """Print current progress to stderr."""
        current_time = time.time()
        # Avoid printing too frequently
        if current_time - self._last_update_time < 1.0:
            return

        self._last_update_time = current_time

        if self.total_pages and self.total_pages > 0:
            total_str = str(self.total_pages)
        else:
            total_str = "?"

        # Print to stderr so it doesn't interfere with stdout
        print(
            f"Fetching page {self.current_page}/{total_str} ... ({self.record_count} records retrieved)",
            file=sys.stderr,
            flush=True
        )

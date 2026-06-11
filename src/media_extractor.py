"""
Media resolution from WhatsApp export ZIPs or alongside plain .txt files.

MediaExtractor is constructed once per source file and reused across all
chunks from that file.  It opens the ZIP exactly once and holds it for the
full sync run, then must be closed (or used as a context manager).
"""

import logging
import mimetypes
import zipfile
from pathlib import Path
from typing import Optional

from src.config import MAX_ZIP_DECOMPRESSED_BYTES

log = logging.getLogger(__name__)


class MediaExtractor:
    """Resolve an attachment filename → (bytes, mime_type), or None if not found.

    Handles two export modes:
      • ZIP archive   — opens once, builds a case-insensitive basename index.
      • Plain .txt    — looks for the file alongside the .txt in the same dir.

    Usage (context manager preferred):
        with MediaExtractor(source_path) as extractor:
            result = extractor.resolve("IMG-20250314-WA0001.jpg")
            if result:
                data, mime_type = result

    Manual usage:
        ext = MediaExtractor(source_path)
        ...
        ext.close()
    """

    def __init__(self, source_path: Path) -> None:
        self._source_path = source_path
        self._zipfile: Optional[zipfile.ZipFile] = None
        self._zip_index: dict[str, str] = {}   # lowercase basename → full zip entry name

        if zipfile.is_zipfile(source_path):
            try:
                self._zipfile = zipfile.ZipFile(source_path, "r")
                # Zip-bomb guard: check total uncompressed size before indexing.
                total_uncompressed = sum(zi.file_size for zi in self._zipfile.infolist())
                if total_uncompressed > MAX_ZIP_DECOMPRESSED_BYTES:
                    self._zipfile.close()
                    self._zipfile = None
                    raise ValueError(
                        f"ZIP archive {source_path.name!r} would decompress to "
                        f"{total_uncompressed:,} bytes, which exceeds the "
                        f"{MAX_ZIP_DECOMPRESSED_BYTES:,}-byte safety limit."
                    )
                for name in self._zipfile.namelist():
                    basename_lower = Path(name).name.lower()
                    # Keep first occurrence; ZIP may have duplicate basenames in
                    # different virtual sub-directories.
                    self._zip_index.setdefault(basename_lower, name)
                log.debug(
                    "MediaExtractor: opened ZIP %s — %d media entries indexed",
                    source_path.name, len(self._zip_index),
                )
            except zipfile.BadZipFile as exc:
                log.warning("MediaExtractor: could not open ZIP %s: %s", source_path.name, exc)
                self._zipfile = None
        else:
            log.debug(
                "MediaExtractor: plain-file mode for %s — will look in %s",
                source_path.name, source_path.parent,
            )

    # ------------------------------------------------------------------
    # Context manager
    # ------------------------------------------------------------------

    def __enter__(self) -> "MediaExtractor":
        return self

    def __exit__(self, *_) -> None:
        self.close()

    def close(self) -> None:
        if self._zipfile is not None:
            self._zipfile.close()
            self._zipfile = None

    # ------------------------------------------------------------------
    # Resolution
    # ------------------------------------------------------------------

    def resolve(self, filename: str) -> Optional[tuple[bytes, str]]:
        """Return (bytes, mime_type) for filename, or None if not found.

        Lookup is case-insensitive on the basename.
        """
        key = Path(filename).name.lower()

        data: Optional[bytes] = None

        if self._zipfile is not None:
            zip_entry = self._zip_index.get(key)
            if zip_entry is None:
                log.debug("MediaExtractor: %r not found in ZIP", filename)
                return None
            try:
                data = self._zipfile.read(zip_entry)
            except Exception as exc:
                log.warning("MediaExtractor: failed to read %r from ZIP: %s", zip_entry, exc)
                return None
        else:
            # Plain-file mode: check same directory as the source .txt.
            # Use only the basename to prevent path-traversal via a crafted filename.
            candidate = self._source_path.parent / Path(filename).name
            if not candidate.exists():
                # Case-insensitive scan for file systems that need it.
                candidate = None
                try:
                    for sibling in self._source_path.parent.iterdir():
                        if sibling.is_file() and sibling.name.lower() == key:
                            candidate = sibling
                            break
                except OSError:
                    pass
            if candidate is None or not candidate.exists():
                log.debug("MediaExtractor: %r not found alongside %s", filename, self._source_path.name)
                return None
            try:
                data = candidate.read_bytes()
            except Exception as exc:
                log.warning("MediaExtractor: failed to read %s: %s", candidate, exc)
                return None

        mime_type, _ = mimetypes.guess_type(filename)
        return data, (mime_type or "application/octet-stream")

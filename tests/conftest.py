from datetime import datetime
from pathlib import Path

import pytest

from src import config
from src.parser import ParsedMessage
from src.state import init_db

FIXTURES_DIR = Path(__file__).parent / "fixtures"


@pytest.fixture
def tmp_root(tmp_path):
    """Point src.config at a throwaway root, proving set_root() rebinds
    every derived path constant (this is the exact call an Android/Chaquopy
    entry point makes before importing sync_manager/mail_client/parser)."""
    config.set_root(tmp_path)
    yield tmp_path
    # Restore the real project root so later tests / a real run aren't left
    # pointed at a deleted tmp_path.
    config.set_root(Path(__file__).parent.parent)


@pytest.fixture
def db_path(tmp_root):
    path = config.STATE_DB_PATH
    init_db(path)
    return path


@pytest.fixture
def sample_messages():
    """A handful of ParsedMessage objects for renderer/chunking tests that
    don't need a real export file."""
    return [
        ParsedMessage(
            chat_id="test_chat",
            timestamp=datetime(2025, 3, 14, 9, 41, 0),
            sender="Alice",
            body="Hey there!",
        ),
        ParsedMessage(
            chat_id="test_chat",
            timestamp=datetime(2025, 3, 14, 9, 41, 30),
            sender="You",
            body="Hi Alice, how are you?",
        ),
        ParsedMessage(
            chat_id="test_chat",
            timestamp=datetime(2025, 3, 14, 9, 42, 0),
            sender="Alice",
            body="IMG-20250314-WA0001.jpg (file attached)",
            attachment_filename="IMG-20250314-WA0001.jpg",
        ),
    ]

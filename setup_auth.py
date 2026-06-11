"""
Standalone OAuth2 initialisation script.

Run this once before the first sync to authorise the app and cache the token:

    python setup_auth.py

On success the token is saved to auth/token.json. Subsequent runs of cli.py
will refresh it automatically without opening a browser.
"""

import sys
from pathlib import Path

# Allow running from the project root without installing as a package.
sys.path.insert(0, str(Path(__file__).parent))

from src.config import CREDENTIALS_FILE, GMAIL_SCOPES, TOKEN_FILE
from src.gmail_client import get_credentials


def main() -> None:
    print("WA Chat Sync — OAuth2 Setup")
    print("=" * 40)

    if not CREDENTIALS_FILE.exists():
        print(
            f"\nERROR: credentials.json not found at:\n  {CREDENTIALS_FILE}\n\n"
            "Steps to fix:\n"
            "  1. Go to https://console.cloud.google.com/\n"
            "  2. Create a project (or select an existing one).\n"
            "  3. Enable the Gmail API.\n"
            "  4. Create OAuth 2.0 credentials (Desktop app).\n"
            "  5. Download the JSON and save it as:\n"
            f"     {CREDENTIALS_FILE}\n"
            "  6. Re-run this script.\n",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"\nCredentials file found: {CREDENTIALS_FILE}")
    print(f"Scopes requested:")
    for scope in GMAIL_SCOPES:
        print(f"  - {scope}")
    print()

    try:
        creds = get_credentials(CREDENTIALS_FILE, TOKEN_FILE)
    except Exception as exc:
        print(f"\nAuthorisation failed: {exc}", file=sys.stderr)
        sys.exit(1)

    print(f"\nAuthorisation successful!")
    print(f"Token saved to: {TOKEN_FILE}")
    print()
    print("You can now run:  python cli.py sync --dry-run")


if __name__ == "__main__":
    main()

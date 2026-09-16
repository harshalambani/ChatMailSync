"""The chat detail screen reaches helpers that live in ChatsListScreen.kt.

Until 2.1.5 this file also held the Windows detail panel to Android's wording
(tag windows-final). The per-chat cutoff sentence is pinned by CutoffDateTest.kt.
"""

from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
CHATS_LIST_KT = (
    REPO_ROOT / "android" / "app" / "src" / "main" / "java" / "com"
    / "chatmailsync" / "app" / "ChatsListScreen.kt"
)


def test_android_status_helpers_stay_reachable_from_the_detail_screen():
    """chatStatusOf/StatusDot/ChatStatus are shared, not file-private.

    ChatDetailScreen calls all three. Kotlin's `private` on a top-level
    declaration is file-private, so re-adding it would break the build --
    which the Kotlin compiler catches, but only if someone runs it.
    """
    source = CHATS_LIST_KT.read_text(encoding="utf-8")
    for decl in (
        "internal enum class ChatStatus(",
        "internal fun chatStatusOf(",
        "internal fun StatusDot(",
    ):
        assert decl in source, f"expected `{decl}` in {CHATS_LIST_KT.name}"

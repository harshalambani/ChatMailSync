"""Work out which sender in an export is the account owner.

A WhatsApp export does not mark your own messages in any way. It writes your
profile name in the sender position exactly as it writes everybody else's:

    18/11/25, 16:18 - Harshal Ambani: on my way

For a long time the renderer assumed that name was the literal string "You",
which is what the fictional demo exports used and what the test fixtures were
built from. Against a real export that assumption is silently false for every
single message: nothing ever matches, so every bubble renders as incoming and
the whole conversation collapses to one side. Three surfaces agreeing with each
other -- renderer, fixtures, demo generator -- is not three pieces of evidence.

The rules below are deliberately conservative. Guessing wrong is worse than not
guessing: an under-detected chat renders the way it always did and can be fixed
by setting the name, whereas a wrong guess silently attributes somebody else's
messages to you, in an archive you are keeping precisely because you trust it.

Resolution order, first match wins:

  1. An explicit name the user has set. Always wins.
  2. The one-to-one rule (`derive_from_one_to_one`) applied to *this* export,
     which is provable rather than probabilistic.
  3. A name already learned from an earlier one-to-one chat. A fresh derivation
     outranks it because a profile-name change is the ordinary reason the two
     disagree, and the export in hand is the newer evidence.
  4. The literal "You", which is both the historical behaviour and the correct
     answer for exports that really do use it.

Note what is *not* here: anything based on how often a name appears. In a group
chat the most frequent sender is very often not you, and an identity inferred
from message counts is exactly the silent mis-attribution described above.
Self is only ever *derived* from a one-to-one chat. Group chats consume a name
that a one-to-one already established, or the user's explicit setting, and
otherwise fall back.
"""

from __future__ import annotations

from typing import Iterable, Optional

# The historical sender string, and a real one: some exports genuinely use it.
FALLBACK = "You"


def _normalise(name: str) -> str:
    """Fold a sender for comparison.

    WhatsApp sometimes prepends U+200E (LEFT-TO-RIGHT MARK) to a field, and
    casing of a profile name is not stable across exports.
    """
    return name.replace("‎", "").strip().casefold()


def derive_from_one_to_one(
    display_name: str, senders: Iterable[str]
) -> Optional[str]:
    """Return the owner's name, or None when this export cannot prove it.

    A one-to-one export is the only shape that identifies the owner without
    guesswork. Its filename names the other party -- "WhatsApp Chat with
    Priya Nair.txt" -- and exactly two people ever speak in it. Match the
    filename against the senders and whoever is left is, necessarily, you.

    Any other shape returns None:

    * A group chat's filename is the group's name and matches no sender.
    * A one-to-one where only one person ever spoke has nobody left over.
    * A chat where the filename matches neither sender (a file renamed by
      hand, say) proves nothing and is not guessed at.
    """
    unique: list[str] = []
    seen: set[str] = set()
    for sender in senders:
        key = _normalise(sender)
        if key and key not in seen:
            seen.add(key)
            unique.append(sender)

    if len(unique) != 2:
        return None

    target = _normalise(display_name)
    if not target:
        return None

    matched = [s for s in unique if _normalise(s) == target]
    if len(matched) != 1:
        # Neither matched, or -- pathologically -- both did. Either way the
        # export has not identified anybody, so say so.
        return None

    return next(s for s in unique if _normalise(s) != target)


def resolve(
    override: Optional[str] = None,
    learned: Optional[str] = None,
    display_name: str = "",
    senders: Optional[Iterable[str]] = None,
) -> tuple[str, Optional[str]]:
    """Resolve the owner's name for one chat.

    Returns:
        (name, newly_derived) -- `name` is what the renderer should treat as
        outgoing, and `newly_derived` is a name proven by *this* export that
        the caller should persist, or None when nothing new was learned.

        `newly_derived` is returned even when an override is in force, so that
        the learned value stays current underneath it; the override still wins
        for rendering. It is deliberately not returned when it merely confirms
        what was already stored.
    """
    derived = (
        derive_from_one_to_one(display_name, senders) if senders is not None else None
    )

    newly_derived = None
    if derived is not None and _normalise(derived) != _normalise(learned or ""):
        # A later one-to-one disagreeing with the stored name is the ordinary
        # consequence of changing your WhatsApp profile name, so the newer
        # derivation wins rather than being discarded as a conflict.
        newly_derived = derived

    if override and override.strip():
        return override.strip(), newly_derived

    if derived is not None:
        return derived, newly_derived

    if learned and learned.strip():
        return learned.strip(), None

    return FALLBACK, None


def is_outgoing(sender: str, self_name: Optional[str]) -> bool:
    """Return True when `sender` is the account owner.

    `self_name` of None means nothing has been established yet, which is the
    fallback case rather than an error: it matches the literal "You" and
    nothing else, preserving the behaviour exports of that shape rely on.
    """
    return _normalise(sender) == _normalise(self_name or FALLBACK)


# Kept here rather than in either front-end so both say exactly the same thing.
# The wording is deliberately about *messages*, not about identity: what the
# user is being told is which bubbles will be drawn as theirs.
_UNKNOWN_SUMMARY = "Not worked out yet"
_UNKNOWN_DETAIL = (
    "Until the app knows which name is yours, every message is shown as if "
    "somebody else sent it. Sync any one-to-one chat and the app works it out "
    "on its own -- the export names the other person, so the only other name "
    "in it is yours. Or type your WhatsApp profile name here."
)
_LEARNED_DETAIL = (
    "Worked out from a one-to-one chat, where the export named the other "
    "person and left exactly one name over. Messages from this name are shown "
    "as yours. If it is wrong, type the right one here and the app will stop "
    "working it out."
)
_OVERRIDE_DETAIL = (
    "You set this name, so the app will not change it. Messages from this "
    "name are shown as yours. Clear the box to let the app work it out from a "
    "one-to-one chat again."
)


def describe(
    override: Optional[str] = None, learned: Optional[str] = None
) -> dict:
    """Describe who the app currently thinks the owner is, for display.

    A name inferred from the user's own files decides which side every bubble
    is drawn on, and that is too consequential to happen silently -- if the app
    has got it wrong, the archive is wrong, and the user is the only one who
    can say so. So both front-ends show this, and both show the same words.

    Returns {"name", "source", "summary", "detail"} where `source` is one of
    "override", "learned" or "unknown". `name` is None only when unknown.
    """
    if override and override.strip():
        name = override.strip()
        return {
            "name": name,
            "source": "override",
            "summary": f"Your messages are the ones from {name}",
            "detail": _OVERRIDE_DETAIL,
        }
    if learned and learned.strip():
        name = learned.strip()
        return {
            "name": name,
            "source": "learned",
            "summary": f"Your messages are the ones from {name}",
            "detail": _LEARNED_DETAIL,
        }
    return {
        "name": None,
        "source": "unknown",
        "summary": _UNKNOWN_SUMMARY,
        "detail": _UNKNOWN_DETAIL,
    }

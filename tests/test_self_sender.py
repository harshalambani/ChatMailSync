"""Tests for working out which sender in an export is the account owner.

The defect these cover was invisible for a long time because everything the
suite had to look at agreed with it: the renderer matched the literal "You",
the fixtures wrote "You", and the demo generator wrote "You". A real export
writes your WhatsApp profile name instead, so every one of the owner's own
messages rendered as incoming and the whole conversation collapsed to one
side. Several surfaces agreeing with each other is not several pieces of
evidence.
"""

from datetime import datetime

import pytest

from src import self_sender
from src.html_renderer import render_chunk
from src.parser import ParsedMessage


# ---------------------------------------------------------------------------
# The one-to-one rule
# ---------------------------------------------------------------------------

def test_one_to_one_leaves_exactly_one_name_over():
    # "WhatsApp Chat with Priya Nair.txt" names the other party, so whichever
    # of the two speakers is not her is necessarily the owner.
    assert self_sender.derive_from_one_to_one(
        "Priya Nair", ["Priya Nair", "Harshal Ambani", "Priya Nair"]
    ) == "Harshal Ambani"


def test_group_chat_derives_nothing():
    # A group export's filename is the group's name and matches no sender.
    # Four people speak and none of them is identified, which is precisely
    # why a group may only consume a name a one-to-one has already proved.
    assert self_sender.derive_from_one_to_one(
        "ShyamKunj201", ["Ramesh", "Farah", "Sunil", "Harshal Ambani"]
    ) is None


def test_one_to_one_where_only_one_person_spoke_derives_nothing():
    # Nobody is left over, so there is nothing to conclude.
    assert self_sender.derive_from_one_to_one("Priya Nair", ["Priya Nair"]) is None


def test_filename_matching_neither_sender_derives_nothing():
    # A hand-renamed file. Two senders, but the export has not said which of
    # them is the counterparty, so guessing would be a coin toss on identity.
    assert self_sender.derive_from_one_to_one(
        "holiday chat", ["Priya Nair", "Harshal Ambani"]
    ) is None


def test_derivation_ignores_case_and_the_ltr_mark():
    # WhatsApp prepends U+200E to fields in some exports, and the casing of a
    # profile name is not stable between them.
    assert self_sender.derive_from_one_to_one(
        "Priya Nair", ["‎PRIYA NAIR", "Harshal Ambani"]
    ) == "Harshal Ambani"


# ---------------------------------------------------------------------------
# Resolution order
# ---------------------------------------------------------------------------

def test_override_beats_a_derivation_that_disagrees():
    name, _ = self_sender.resolve(
        override="Harshal",
        display_name="Priya Nair",
        senders=["Priya Nair", "Harshal Ambani"],
    )
    assert name == "Harshal"


def test_override_still_lets_the_learned_name_stay_current():
    # The override wins for rendering, but the derivation underneath it is
    # still true and is still worth storing -- otherwise clearing the override
    # later would drop the app back to a stale name, or to none at all.
    name, newly_derived = self_sender.resolve(
        override="Harshal",
        learned=None,
        display_name="Priya Nair",
        senders=["Priya Nair", "Harshal Ambani"],
    )
    assert name == "Harshal"
    assert newly_derived == "Harshal Ambani"


def test_group_chat_uses_the_name_a_one_to_one_established():
    name, newly_derived = self_sender.resolve(
        learned="Harshal Ambani",
        display_name="ShyamKunj201",
        senders=["Ramesh", "Farah", "Harshal Ambani"],
    )
    assert name == "Harshal Ambani"
    assert newly_derived is None


def test_nothing_known_falls_back_to_you_rather_than_guessing():
    # The fallback is not a failure: exports that really do write "You" exist,
    # and rendering as this always did is the recoverable outcome. Attributing
    # somebody else's messages to the user is not.
    name, newly_derived = self_sender.resolve(
        display_name="ShyamKunj201",
        senders=["Ramesh", "Farah", "Harshal Ambani"],
    )
    assert name == self_sender.FALLBACK
    assert newly_derived is None


def test_the_most_frequent_sender_is_never_taken_as_the_owner():
    # The rule deliberately not implemented. Ramesh sends most of the messages
    # in this group; inferring identity from message counts would label every
    # one of them as the user's own, in an archive kept because it is trusted.
    name, _ = self_sender.resolve(
        display_name="Building Society",
        senders=["Ramesh", "Ramesh", "Ramesh", "Farah", "Harshal Ambani"],
    )
    assert name == self_sender.FALLBACK


def test_a_newer_one_to_one_corrects_a_stale_learned_name():
    # Changing your WhatsApp profile name is ordinary, and the export proves
    # the new one, so the newer derivation wins rather than being treated as a
    # conflict to be reported.
    name, newly_derived = self_sender.resolve(
        learned="H. Ambani",
        display_name="Priya Nair",
        senders=["Priya Nair", "Harshal Ambani"],
    )
    assert name == "Harshal Ambani"
    assert newly_derived == "Harshal Ambani"


def test_a_derivation_confirming_the_stored_name_is_not_a_new_fact():
    _, newly_derived = self_sender.resolve(
        learned="Harshal Ambani",
        display_name="Priya Nair",
        senders=["Priya Nair", "‎harshal ambani"],
    )
    assert newly_derived is None


@pytest.mark.parametrize("blank", ["", "   ", None])
def test_a_blank_override_is_an_absent_override(blank):
    name, _ = self_sender.resolve(
        override=blank,
        learned="Harshal Ambani",
        display_name="ShyamKunj201",
        senders=["Ramesh", "Harshal Ambani"],
    )
    assert name == "Harshal Ambani"


# ---------------------------------------------------------------------------
# What the renderer does with it
# ---------------------------------------------------------------------------

def _msg(sender, body):
    return ParsedMessage(
        chat_id="test_chat",
        timestamp=datetime(2025, 3, 14, 9, 41, 0),
        sender=sender,
        body=body,
    )


def test_renderer_puts_the_named_owner_on_the_outgoing_side():
    rendered = render_chunk(
        [_msg("Harshal Ambani", "on my way"), _msg("Priya Nair", "see you")],
        "Priya Nair",
        extractor=None,
        self_sender="Harshal Ambani",
    )
    owner_side = rendered.html_body.index("flex-end")
    other_side = rendered.html_body.index("flex-start")
    assert owner_side < other_side          # the owner's message came first
    assert "#DCF8C6" in rendered.html_body  # and got the outgoing bubble


def test_renderer_without_a_name_still_honours_the_literal_you():
    # Exports of that shape exist and must keep working; this is the default
    # every caller gets until a name has been established.
    rendered = render_chunk(
        [_msg("You", "outgoing"), _msg("Alice", "incoming")],
        "Alice",
        extractor=None,
    )
    assert "flex-end" in rendered.html_body


def test_renderer_without_a_name_draws_a_real_export_entirely_incoming():
    # The defect itself, pinned so it cannot come back silently: given a real
    # export and no resolved name, nothing is outgoing.
    rendered = render_chunk(
        [_msg("Harshal Ambani", "on my way"), _msg("Priya Nair", "see you")],
        "Priya Nair",
        extractor=None,
    )
    assert "flex-end" not in rendered.html_body


# ---------------------------------------------------------------------------
# What the front-ends are told
# ---------------------------------------------------------------------------

def test_an_unknown_owner_says_so_rather_than_showing_an_empty_box():
    # The defect was invisible partly because nothing on screen claimed
    # anything. A blank field reads as "nothing to set here", not as "the app
    # has not worked out which messages are yours".
    described = self_sender.describe()
    assert described["name"] is None
    assert described["source"] == "unknown"
    assert described["summary"] == "Not worked out yet"
    assert "one-to-one" in described["detail"]


def test_a_learned_name_is_shown_as_learned():
    described = self_sender.describe(learned="Harshal Ambani")
    assert described["name"] == "Harshal Ambani"
    assert described["source"] == "learned"
    assert "Harshal Ambani" in described["summary"]


def test_an_override_is_shown_as_the_user_s_own_choice():
    # It matters that these two read differently: one is a fact the app worked
    # out and might revise, the other is a decision it will not touch.
    described = self_sender.describe(override="Harshal", learned="Harshal Ambani")
    assert described["name"] == "Harshal"
    assert described["source"] == "override"
    assert self_sender.describe(learned="Harshal Ambani")["detail"] != described["detail"]


@pytest.mark.parametrize("blank", ["", "   ", None])
def test_a_blank_override_is_described_as_the_learned_name(blank):
    described = self_sender.describe(override=blank, learned="Harshal Ambani")
    assert described["source"] == "learned"


def test_describe_agrees_with_resolve_on_who_the_owner_is():
    # Two code paths, one answer: a screen that said one thing while the
    # renderer did another would be worse than saying nothing at all.
    for override, learned in (
        (None, None),
        (None, "Harshal Ambani"),
        ("Harshal", "Harshal Ambani"),
    ):
        resolved, _ = self_sender.resolve(override=override, learned=learned)
        described = self_sender.describe(override, learned)
        assert (described["name"] or self_sender.FALLBACK) == resolved

package com.chatmailsync.core.mail

/**
 * BUG-11 deliberate differences from the Python goldens. Python saves the chat's mail ids
 * and the per-sender pushed counts only AFTER a whole push, so a push cut short at email k
 * left the ids empty (the rest of the chat started a new thread) and the first k emails'
 * senders uncounted. Kotlin saves both as each email is accepted, in the same transaction
 * as that email's hashes. Each entry is the state this port reaches at that step: the
 * senders are the TOTAL pushed across every run of the chat, and the ids are those of the
 * first accepted email. Every other column, step and scenario still matches Python exactly.
 */
internal object Bug11Differences {
    val chats: Map<String, String> = mapOf(
        "recovery_resumes_after_a_crash step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_runs_before_new_files step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_crashes_again_then_finishes step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_crashes_again_then_finishes step 2" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_honours_a_cutoff_set_after_the_crash step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_cutoff_beyond_everything_leaves_nothing_to_push step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_cutoff_beyond_everything_leaves_nothing_to_push step 2" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_source_file_missing step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_source_file_missing step 2" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_chat_record_missing step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_parse_error step 1" to "[[asha_rao, Asha Rao, t1, Label_WA, <anchor>, WhatsApp Chat with Asha Rao.zip]]",
        "recovery_parse_error step 2" to "[[asha_rao, Asha Rao, t1, Label_WA, <anchor>, WhatsApp Chat with Asha Rao.zip]]",
        "recovery_push_fails_again step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_push_fails_again step 2" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_push_fails_again step 3" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_dry_run_changes_nothing step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_dry_run_changes_nothing step 2" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_reparse_finds_no_messages step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "recovery_reparse_finds_no_messages step 2" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
        "push_failure_leaves_file_and_partial_hashes step 1" to "[[meera_iyer, Meera Iyer, t1, Label_WA, <anchor>, WhatsApp Chat with Meera Iyer.txt]]",
    )
    val senders: Map<String, String> = mapOf(
        "recovery_resumes_after_a_crash step 1" to "[[meera_iyer, Meera Iyer, 1], [meera_iyer, Rohan Mehta, 1]]",
        "recovery_resumes_after_a_crash step 2" to "[[meera_iyer, Meera Iyer, 2], [meera_iyer, Rohan Mehta, 2]]",
        "recovery_resumes_after_a_crash step 3" to "[[meera_iyer, Meera Iyer, 2], [meera_iyer, Rohan Mehta, 2]]",
        "recovery_runs_before_new_files step 1" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_runs_before_new_files step 2" to "[[meera_iyer, Meera Iyer, 2], [meera_iyer, Rohan Mehta, 2], [rohan_mehta, Meera Iyer, 1], [rohan_mehta, Rohan Mehta, 1]]",
        "recovery_crashes_again_then_finishes step 1" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_crashes_again_then_finishes step 2" to "[[meera_iyer, Meera Iyer, 1], [meera_iyer, Rohan Mehta, 1]]",
        "recovery_crashes_again_then_finishes step 3" to "[[meera_iyer, Meera Iyer, 2], [meera_iyer, Rohan Mehta, 2]]",
        "recovery_honours_a_cutoff_set_after_the_crash step 1" to "[[meera_iyer, Meera Iyer, 1], [meera_iyer, Rohan Mehta, 1]]",
        "recovery_honours_a_cutoff_set_after_the_crash step 2" to "[[meera_iyer, Meera Iyer, 1], [meera_iyer, Rohan Mehta, 2]]",
        "recovery_cutoff_beyond_everything_leaves_nothing_to_push step 1" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_cutoff_beyond_everything_leaves_nothing_to_push step 2" to "[[meera_iyer, Meera Iyer, 1], [meera_iyer, Rohan Mehta, 0]]",
        "recovery_source_file_missing step 1" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_source_file_missing step 2" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_chat_record_missing step 1" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_chat_record_missing step 2" to "[[meera_iyer, Meera Iyer, 2], [meera_iyer, Rohan Mehta, 2]]",
        "recovery_parse_error step 1" to "[[asha_rao, Meera Iyer, 1]]",
        "recovery_parse_error step 2" to "[[asha_rao, Meera Iyer, 1]]",
        "recovery_push_fails_again step 1" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_push_fails_again step 2" to "[[meera_iyer, Meera Iyer, 2], [meera_iyer, Rohan Mehta, 2]]",
        "recovery_push_fails_again step 3" to "[[meera_iyer, Meera Iyer, 2], [meera_iyer, Rohan Mehta, 2]]",
        "recovery_dry_run_changes_nothing step 1" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_dry_run_changes_nothing step 2" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_dry_run_changes_nothing step 3" to "[[meera_iyer, Meera Iyer, 2], [meera_iyer, Rohan Mehta, 2]]",
        "recovery_reparse_finds_no_messages step 1" to "[[meera_iyer, Meera Iyer, 1]]",
        "recovery_reparse_finds_no_messages step 2" to "[[meera_iyer, Meera Iyer, 1]]",
        "push_failure_leaves_file_and_partial_hashes step 1" to "[[meera_iyer, Meera Iyer, 1]]",
        "push_failure_leaves_file_and_partial_hashes step 2" to "[[meera_iyer, Meera Iyer, 2], [meera_iyer, Rohan Mehta, 1]]",
    )
}

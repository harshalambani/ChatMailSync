package com.chatmailsync.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

// QA-02 throwaway negative check. Reverted before handover.
@RunWith(AndroidJUnit4::class)
class Qa02NegativeTest {
    @Test
    fun deliberatelyFails() {
        fail("QA-02 negative check: this must fail the job")
    }
}

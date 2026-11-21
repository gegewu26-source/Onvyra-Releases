package com.constx.onvyra.data.ai.tools
import org.junit.Assert.*
import org.junit.Test
class AndroidControlApprovalsTest {
    @Test fun pendingIsNotAnApproval() {AndroidControlApprovals.remember("pending","tap","{}","f");assertNull(AndroidControlApprovals.consume("pending","tap","{}"))}
    @Test fun approvedTicketIsConsumedOnce() {AndroidControlApprovals.remember("once","tap","{}","f");AndroidControlApprovals.approve("once","tap","{}");assertEquals("f",AndroidControlApprovals.consume("once","tap","{}"));assertNull(AndroidControlApprovals.consume("once","tap","{}"))}
    @Test fun otherConversationsAndArgumentsCannotConsumeApproval() {AndroidControlApprovals.remember("owner","tap","{a}","f");AndroidControlApprovals.approve("other","tap","{a}");AndroidControlApprovals.approve("owner","tap","{b}");assertNull(AndroidControlApprovals.consume("owner","tap","{a}"))}
    @Test fun approvalCannotBeInventedWithoutPendingObservation() {AndroidControlApprovals.approve("missing","tap","{}");assertNull(AndroidControlApprovals.consume("missing","tap","{}"))}
}

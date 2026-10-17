package com.laioffer.onlineorder.datasource;


import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.TransactionExecution;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;


class ReadYourWritesTrackerTests {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ReadYourWritesTracker tracker = new ReadYourWritesTracker(Duration.ofSeconds(5), now::get);


    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }


    @Test
    void aCommittedWriteKeepsTheSessionOnThePrimaryForTheWindow() {
        MockHttpSession session = new MockHttpSession();
        bind(requestWith(session));
        Assertions.assertFalse(tracker.readsMustUsePrimary());

        tracker.afterCommit(execution(false), null);
        Assertions.assertTrue(tracker.readsMustUsePrimary());
        Assertions.assertEquals(now.get(), session.getAttribute(ReadYourWritesTracker.LAST_WRITE_ATTRIBUTE));

        // The session's next request, possibly on another instance.
        bind(requestWith(session));
        now.addAndGet(4_999);
        Assertions.assertTrue(tracker.readsMustUsePrimary());
        now.addAndGet(1);
        Assertions.assertFalse(tracker.readsMustUsePrimary());
    }


    @Test
    void aWriteWithoutASessionCoversTheRestOfItsRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        bind(request);

        tracker.recordWrite();

        Assertions.assertTrue(tracker.readsMustUsePrimary());
        Assertions.assertNull(request.getSession(false));
    }


    @Test
    void readOnlyAndFailedCommitsStartNoWindow() {
        bind(requestWith(new MockHttpSession()));

        tracker.afterCommit(execution(true), null);
        tracker.afterCommit(execution(false), new IllegalStateException("commit failed"));

        Assertions.assertFalse(tracker.readsMustUsePrimary());
    }


    @Test
    void workOutsideAnHttpRequestIsNeverSticky() {
        tracker.afterCommit(execution(false), null);
        Assertions.assertFalse(tracker.readsMustUsePrimary());
    }


    @Test
    void anInvalidatedSessionIsNoError() {
        // Logout invalidates the session in the request that also wrote (the failed-login counter reset, say).
        HttpSession invalidated = Mockito.mock(HttpSession.class);
        Mockito.doThrow(new IllegalStateException("invalidated")).when(invalidated).setAttribute(Mockito.anyString(), Mockito.any());
        Mockito.when(invalidated.getAttribute(Mockito.anyString())).thenThrow(new IllegalStateException("invalidated"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(invalidated);
        bind(request);

        tracker.recordWrite();
        Assertions.assertTrue(tracker.readsMustUsePrimary(), "the request itself still remembers the write");

        MockHttpServletRequest next = new MockHttpServletRequest();
        next.setSession(invalidated);
        bind(next);
        Assertions.assertFalse(tracker.readsMustUsePrimary());
    }


    @Test
    void aZeroWindowTurnsReadYourWritesOff() {
        ReadYourWritesTracker off = new ReadYourWritesTracker(Duration.ZERO, now::get);
        bind(requestWith(new MockHttpSession()));

        off.recordWrite();

        Assertions.assertFalse(off.readsMustUsePrimary());
    }


    @Test
    void anUnexpectedAttributeValueIsIgnored() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReadYourWritesTracker.LAST_WRITE_ATTRIBUTE, "yesterday");
        bind(requestWith(session));

        Assertions.assertFalse(tracker.readsMustUsePrimary());
    }


    private static MockHttpServletRequest requestWith(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }


    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }


    private static TransactionExecution execution(boolean readOnly) {
        TransactionExecution execution = Mockito.mock(TransactionExecution.class);
        Mockito.when(execution.isReadOnly()).thenReturn(readOnly);
        return execution;
    }
}

package com.fixai.platform.fixgateway.adapter.out.quickfix;

import org.junit.jupiter.api.Test;
import quickfix.SessionID;

import static org.assertj.core.api.Assertions.assertThat;

class QuickFixSessionLookupAdapterTest {

    @Test
    void shouldReturnEmptyWhenSessionIsNotRegistered() {
        QuickFixSessionLookupAdapter adapter = new QuickFixSessionLookupAdapter();

        assertThat(adapter.find(new SessionID("FIX.4.4", "UNKNOWN", "UNKNOWN"))).isEmpty();
    }
}

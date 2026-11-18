package dev.agenvas.testing;

import static org.assertj.core.api.Assertions.assertThat;
import org.jooq.DSLContext;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

public final class QueryCounts {
    private QueryCounts() {
    }

    public static int countQueries(DSLContext db, Runnable read) {
        var config = db.configuration();
        var original = config.executeListenerProviders();
        var calls = new AtomicInteger();
        var reader = Thread.currentThread();
        try {
            config.setAppending(new ExecuteListener() {
                @Override
                public void executeEnd(ExecuteContext ctx) {
                    if (Thread.currentThread() == reader)
                        calls.incrementAndGet();
                }
            });
            read.run();
            assertThat(calls.get()).isPositive();
            return calls.get();
        } finally {
            config.set(original);
        }
    }

}

package io.github.red171.libtray.internal;

import io.github.red171.libtray.TrayEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventDispatcherTest {
    private static final TrayEvent A = TrayEvent.Activated.INSTANCE;

    @Test
    void deliversInOrderOnOwnThread() throws Exception {
        var dispatcher = new EventDispatcher("test");
        var threads = new LinkedBlockingQueue<Thread>();
        var events = new LinkedBlockingQueue<TrayEvent>();
        dispatcher.subscribe(event -> {
            threads.add(Thread.currentThread());
            events.add(event);
        }, null);
        dispatcher.fire(A);
        dispatcher.fire(TrayEvent.MenuRequested.INSTANCE);
        assertSame(A, events.poll(2, TimeUnit.SECONDS));
        assertSame(TrayEvent.MenuRequested.INSTANCE, events.poll(2, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), threads.poll());
        dispatcher.close();
    }

    @Test
    void blockingListenerDoesNotBlockFire() throws Exception {
        var dispatcher = new EventDispatcher("test");
        var release = new CountDownLatch(1);
        dispatcher.subscribe(event -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
        }, null);
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            dispatcher.fire(A);
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
        dispatcher.close();
        release.countDown();
    }

    @Test
    void survivesThrowingListenerThrowingExecutorAndLeftInterrupt() throws Exception {
        var dispatcher = new EventDispatcher("test");
        var events = new LinkedBlockingQueue<TrayEvent>();
        dispatcher.subscribe(event -> {
            throw new IllegalStateException("listener");
        }, null);
        dispatcher.subscribe(event -> {
        }, command -> {
            throw new IllegalStateException("executor");
        });
        dispatcher.subscribe(event -> Thread.currentThread().interrupt(), null);
        dispatcher.subscribe(events::add, null);
        dispatcher.fire(A);
        dispatcher.fire(A);
        assertSame(A, events.poll(2, TimeUnit.SECONDS));
        assertSame(A, events.poll(2, TimeUnit.SECONDS));
        dispatcher.close();
    }

    @Test
    void executorReceivesEventsAndUnsubscribeStopsThem() throws Exception {
        var dispatcher = new EventDispatcher("test");
        var submitted = Collections.synchronizedList(new ArrayList<TrayEvent>());
        var ran = new LinkedBlockingQueue<TrayEvent>();
        Runnable unsubscribe = dispatcher.subscribe(ran::add, command -> {
            submitted.add(A);
            command.run();
        });
        dispatcher.fire(A);
        assertSame(A, ran.poll(2, TimeUnit.SECONDS));
        assertEquals(List.of(A), submitted);
        unsubscribe.run();
        dispatcher.fire(A);
        assertNull(ran.poll(150, TimeUnit.MILLISECONDS));
        dispatcher.close();
    }

    @Test
    void closeDropsLaterEvents() throws Exception {
        var dispatcher = new EventDispatcher("test");
        var events = new LinkedBlockingQueue<TrayEvent>();
        dispatcher.subscribe(events::add, null);
        dispatcher.close();
        dispatcher.fire(A);
        assertNull(events.poll(150, TimeUnit.MILLISECONDS));
    }
}

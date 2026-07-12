package com.iskeru.computeadmin.run;

import com.iskeru.computeadmin.run.service.RunOutputHub;
import com.iskeru.computeadmin.run.service.RunOutputHub.OutputEvent;
import com.iskeru.computeadmin.run.service.RunOutputHub.Subscriber;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The redesigned {@link RunOutputHub} (spec-013): a plain-POJO unit test of the two
 * runtime-hygiene guarantees the hub now makes.
 *
 * <ul>
 *   <li><strong>Eviction</strong> — driven through the {@link RunOutputHub#evict(Instant)}
 *       {@code asOf} seam (no {@code Thread.sleep}): a complete channel is removed past
 *       the retention TTL and by the LRU-by-{@code completedAt} cap, while a live
 *       (incomplete) channel is never evicted. Presence is observed through the
 *       public get-without-create {@link RunOutputHub#attachIfPresent}.</li>
 *   <li><strong>No I/O under the lock</strong> — a subscriber blocked in
 *       {@code onEvent} neither stalls the producer nor the other subscribers, and is
 *       dropped once it lags past the backlog cap (the direct regression test for the
 *       old stall bug).</li>
 * </ul>
 *
 * <p>spec-013.
 */
class RunOutputHubTest {

    private static OutputEvent stdout(String data) {
        return new OutputEvent(OutputEvent.STDOUT, data);
    }

    /** A no-op subscriber whose presence via {@link RunOutputHub#attachIfPresent} probes a channel. */
    private static Subscriber probe() {
        return new Subscriber() {
            @Override
            public void onEvent(OutputEvent event) {
            }

            @Override
            public void onComplete() {
            }
        };
    }

    @Test
    void evict_RemovesCompleteChannelPastTtl_NeverTheLiveRun() {
        RunOutputHub hub = new RunOutputHub(1024, Duration.ofMinutes(10), 256, 8);

        hub.publish("live", stdout("running"));       // never completed
        hub.publish("done", stdout("finished"));
        hub.complete("done", 0);                       // completedAt ~ now

        // asOf far in the past → cutoff earlier still → nothing past the TTL.
        hub.evict(Instant.now().minus(Duration.ofHours(1)));
        assertThat(hub.attachIfPresent("done", probe())).isTrue();

        // asOf past completedAt + retention → the complete channel is evicted...
        hub.evict(Instant.now().plus(Duration.ofMinutes(11)));
        assertThat(hub.attachIfPresent("done", probe())).isFalse();
        // ...but the live run is never evicted, however far asOf advances.
        assertThat(hub.attachIfPresent("live", probe())).isTrue();
    }

    @Test
    void evict_EnforcesLruCapOnCompleteChannels() throws InterruptedException {
        RunOutputHub hub = new RunOutputHub(1024, Duration.ofMinutes(10), 2, 8);

        // Three complete channels with strictly increasing completedAt.
        completeChannel(hub, "c1");
        TimeUnit.MILLISECONDS.sleep(2);
        completeChannel(hub, "c2");
        TimeUnit.MILLISECONDS.sleep(2);
        completeChannel(hub, "c3");

        // asOf = now → all are within the TTL; only the cap (2) applies, dropping the
        // oldest by completedAt.
        hub.evict(Instant.now());

        assertThat(hub.attachIfPresent("c1", probe())).isFalse();
        assertThat(hub.attachIfPresent("c2", probe())).isTrue();
        assertThat(hub.attachIfPresent("c3", probe())).isTrue();
    }

    @Test
    void slowSubscriber_NeitherStallsProducerNorOthers_AndIsDroppedPastBacklog() throws InterruptedException {
        int backlog = 5;
        int total = 40;
        RunOutputHub hub = new RunOutputHub(backlog, Duration.ofMinutes(10), 256, 8);

        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger slowDelivered = new AtomicInteger();
        AtomicBoolean slowDropped = new AtomicBoolean();

        Subscriber slow = new Subscriber() {
            @Override
            public void onEvent(OutputEvent event) {
                // Block on the very first event to simulate a backpressured client.
                if (slowDelivered.getAndIncrement() == 0) {
                    slowEntered.countDown();
                    awaitLatch(release);
                }
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onDropped() {
                slowDropped.set(true);
            }
        };

        AtomicInteger fastDelivered = new AtomicInteger();
        Subscriber fast = new Subscriber() {
            @Override
            public void onEvent(OutputEvent event) {
                fastDelivered.incrementAndGet();
            }

            @Override
            public void onComplete() {
            }
        };

        hub.subscribe("run", slow);
        hub.subscribe("run", fast);

        // First event: the slow subscriber's delivery thread parks inside onEvent.
        hub.publish("run", stdout("0"));
        assertThat(slowEntered.await(2, TimeUnit.SECONDS)).isTrue();

        // The producer publishes the rest on this thread while the slow subscriber is
        // blocked. It must not stall: with the old lock-holding design publish() would
        // block on the slow subscriber's onEvent and this loop would never finish. Flow
        // control waits for the responsive subscriber to keep up (so it never itself
        // lags past the backlog), proving the block is confined to the slow peer.
        long startNanos = System.nanoTime();
        for (int i = 1; i < total; i++) {
            hub.publish("run", stdout(String.valueOf(i)));
            int delivered = i + 1;
            spinUntil(() -> fastDelivered.get() >= delivered);
        }
        long producerMillis = (System.nanoTime() - startNanos) / 1_000_000;

        // The producer drained all events while the slow subscriber stayed blocked...
        assertThat(producerMillis).isLessThan(5_000);
        assertThat(fastDelivered.get()).isEqualTo(total);
        assertThat(slowDropped.get()).isFalse();

        // ...and once released, the slow subscriber is dropped for lagging past the cap.
        release.countDown();
        await().atMost(2, TimeUnit.SECONDS).until(slowDropped::get);
        assertThat(slowDelivered.get()).isLessThan(total);
    }

    private static void spinUntil(java.util.function.BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("condition not met within 2s");
            }
            Thread.onSpinWait();
        }
    }

    private static void completeChannel(RunOutputHub hub, String runId) {
        hub.publish(runId, stdout("out"));
        hub.complete(runId, 0);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

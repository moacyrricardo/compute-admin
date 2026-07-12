package com.iskeru.computeadmin.run.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-run output fan-out. The run task feeds stdout/stderr chunks and a final exit
 * here as they arrive; this hub broadcasts each to every live subscriber (the SSE
 * output endpoint, and the MCP progress consumer of spec 008) and keeps the full
 * append-only <strong>history</strong> so a <strong>late subscriber</strong>
 * receives the buffered prefix and then the live tail — with no lost chunk and no
 * duplication.
 *
 * <p><strong>No I/O under the lock (spec-013).</strong> The delivery model is a
 * single shared history per channel plus a per-subscriber <em>cursor</em> into it.
 * {@code publish}/{@code complete} mutate {@code history}/{@code complete}/
 * {@code completedAt} and snapshot the subscriber set <em>under the channel
 * monitor</em>, then wake deliveries and invoke {@code onEvent}/{@code onComplete}
 * <em>outside</em> the monitor on a small dedicated executor. A slow or
 * backpressured client (SSE {@code eventSink.send}, or the MCP
 * {@code progressNotification}) therefore can never stall the run's producer thread
 * or the other subscribers. The buffered-prefix-then-live-tail invariant holds
 * <em>by construction</em>: a mid-stream subscriber is just a cursor starting at the
 * current history size, so there is no replay-then-switch seam to make atomic.
 *
 * <p>A subscriber that lags past {@code ca.run.output-subscriber-backlog} unread
 * events is dropped (its sink closed via {@link Subscriber#onDropped()}) rather than
 * allowed to block the producer — a bounded buffer, then disconnect.
 *
 * <p><strong>Eviction (spec-013).</strong> A completed channel records
 * {@code completedAt} and is retained so a late subscriber still replays it. The
 * scheduled {@code RunOutputEvictionJob} calls {@link #evict(Instant)}; that seam
 * removes only <em>complete</em> channels past the {@code ca.run.output-retention}
 * TTL and enforces an LRU-by-{@code completedAt} cap
 * ({@code ca.run.output-max-channels}). A live run is never evicted, and
 * {@code history} is never nulled (a still-draining delivery holds a strong
 * reference, so dropping the map entry is GC-safe). State is in memory only (a live
 * view, not the record of truth — {@code RunService} persists the same output onto
 * the append-only {@code Run}).
 *
 * <p>spec-005; redesigned in spec-013.
 */
@Component
public class RunOutputHub {

    /** One streamed event: {@code stream} is {@code stdout}, {@code stderr}, or {@code exit}. */
    public record OutputEvent(String stream, String data) {

        public static final String STDOUT = "stdout";
        public static final String STDERR = "stderr";
        public static final String EXIT = "exit";
    }

    /** A live consumer of a run's output. */
    public interface Subscriber {

        /** A chunk (or the terminal {@code exit} event). */
        void onEvent(OutputEvent event);

        /** The run has completed; no further events will arrive. */
        void onComplete();

        /**
         * The subscriber lagged past the backlog cap and was dropped; the sink must
         * be closed. Defaults to {@link #onComplete()} — for an SSE sink both simply
         * close the stream.
         */
        default void onDropped() {
            onComplete();
        }
    }

    /** A single subscriber's cursor into a channel's history. Guarded by the channel monitor. */
    private static final class Subscription {
        private final Subscriber subscriber;
        private int cursor;
        private boolean scheduled;
        private boolean cancelled;

        private Subscription(Subscriber subscriber) {
            this.subscriber = subscriber;
        }
    }

    /** Mutable per-run state, guarded by its own monitor. */
    private static final class Channel {
        private final List<OutputEvent> history = new ArrayList<>();
        private final List<Subscription> subscriptions = new ArrayList<>();
        private boolean complete;
        private Instant completedAt;
    }

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Executor deliveryExecutor;
    private final int subscriberBacklog;
    private final Duration retention;
    private final int maxChannels;

    public RunOutputHub(
            @Value("${ca.run.output-subscriber-backlog:1024}") int subscriberBacklog,
            @Value("${ca.run.output-retention:10m}") Duration retention,
            @Value("${ca.run.output-max-channels:256}") int maxChannels,
            @Value("${ca.run.output-delivery-threads:8}") int deliveryThreads) {
        this.subscriberBacklog = subscriberBacklog;
        this.retention = retention;
        this.maxChannels = maxChannels;
        // Bounded delivery pool (spec-016): a burst of subscribers can no longer spawn
        // unbounded threads. Per-subscriber memory is already bounded by the backlog
        // cap above; this bounds the thread dimension. Threads stay daemon.
        this.deliveryExecutor = Executors.newFixedThreadPool(
                Math.max(1, deliveryThreads), deliveryThreadFactory());
    }

    private static ThreadFactory deliveryThreadFactory() {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, "run-output-delivery-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        if (deliveryExecutor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdownNow();
        }
    }

    /** Broadcasts an output chunk and appends it to the history. */
    public void publish(String runId, OutputEvent event) {
        Channel channel = channels.computeIfAbsent(runId, k -> new Channel());
        List<Subscription> toWake;
        synchronized (channel) {
            if (channel.complete) {
                return;
            }
            channel.history.add(event);
            toWake = List.copyOf(channel.subscriptions);
        }
        wakeAll(channel, toWake);
    }

    /**
     * Marks the run complete: appends a terminal {@code exit} event, records
     * {@code completedAt}, and wakes every subscriber so each drains the tail and is
     * then completed. Idempotent.
     */
    public void complete(String runId, int exitCode) {
        Channel channel = channels.computeIfAbsent(runId, k -> new Channel());
        List<Subscription> toWake;
        synchronized (channel) {
            if (channel.complete) {
                return;
            }
            channel.complete = true;
            channel.completedAt = Instant.now();
            channel.history.add(new OutputEvent(OutputEvent.EXIT, String.valueOf(exitCode)));
            toWake = List.copyOf(channel.subscriptions);
        }
        wakeAll(channel, toWake);
    }

    /**
     * Subscribes to a live run's output (QUEUED/RUNNING): creates the channel if
     * absent and attaches a cursor at the start of history. The buffered prefix is
     * replayed and the live tail follows, delivered off the producer thread.
     */
    public void subscribe(String runId, Subscriber subscriber) {
        Channel channel = channels.computeIfAbsent(runId, k -> new Channel());
        attach(channel, subscriber);
    }

    /**
     * Attaches a subscriber only if the channel already exists (get-without-create,
     * so an already-evicted run is <em>not</em> resurrected). Returns {@code false}
     * when no channel is present — the caller then replays from the persisted run.
     * The get-check-attach runs under the channel monitor, mirroring {@link #evict};
     * a channel evicted concurrently is still safely drained through the strong
     * reference held here.
     */
    public boolean attachIfPresent(String runId, Subscriber subscriber) {
        Channel channel = channels.get(runId);
        if (channel == null) {
            return false;
        }
        attach(channel, subscriber);
        return true;
    }

    private void attach(Channel channel, Subscriber subscriber) {
        Subscription subscription = new Subscription(subscriber);
        synchronized (channel) {
            channel.subscriptions.add(subscription);
        }
        wake(channel, subscription);
    }

    /**
     * Removes complete channels past the retention TTL relative to {@code asOf}, then
     * trims the remaining complete channels to the LRU-by-{@code completedAt} cap.
     * Live channels are never touched. {@code asOf} is the test seam (no sleep).
     */
    public void evict(Instant asOf) {
        Instant cutoff = asOf.minus(retention);
        List<Entry<String, Channel>> completeChannels = new ArrayList<>();
        for (Entry<String, Channel> entry : channels.entrySet()) {
            Channel channel = entry.getValue();
            Instant completedAt;
            boolean complete;
            synchronized (channel) {
                complete = channel.complete;
                completedAt = channel.completedAt;
            }
            if (!complete) {
                continue;
            }
            if (completedAt != null && !completedAt.isAfter(cutoff)) {
                channels.remove(entry.getKey(), channel);
            } else {
                completeChannels.add(entry);
            }
        }
        int overflow = completeChannels.size() - maxChannels;
        if (overflow <= 0) {
            return;
        }
        completeChannels.sort(Comparator.comparing(e -> completedAtOf(e.getValue())));
        for (int i = 0; i < overflow; i++) {
            Entry<String, Channel> entry = completeChannels.get(i);
            channels.remove(entry.getKey(), entry.getValue());
        }
    }

    private static Instant completedAtOf(Channel channel) {
        synchronized (channel) {
            return channel.completedAt == null ? Instant.EPOCH : channel.completedAt;
        }
    }

    private void wakeAll(Channel channel, List<Subscription> subscriptions) {
        for (Subscription subscription : subscriptions) {
            wake(channel, subscription);
        }
    }

    /** Schedules a single delivery pass for {@code subscription} if one isn't already queued. */
    private void wake(Channel channel, Subscription subscription) {
        synchronized (channel) {
            if (subscription.cancelled || subscription.scheduled) {
                return;
            }
            subscription.scheduled = true;
        }
        deliveryExecutor.execute(() -> drain(channel, subscription));
    }

    /**
     * Delivers pending events to one subscriber, then either parks it (clears the
     * scheduled flag) or completes/drops it. All {@code onEvent}/{@code onComplete}/
     * {@code onDropped} calls happen outside the monitor, so a slow subscriber blocks
     * only its own delivery thread — never the producer or its peers.
     */
    private void drain(Channel channel, Subscription subscription) {
        while (true) {
            OutputEvent event = null;
            boolean complete = false;
            boolean dropped = false;
            synchronized (channel) {
                if (subscription.cancelled) {
                    subscription.scheduled = false;
                    return;
                }
                int size = channel.history.size();
                if (size - subscription.cursor > subscriberBacklog) {
                    subscription.cancelled = true;
                    subscription.scheduled = false;
                    channel.subscriptions.remove(subscription);
                    dropped = true;
                } else if (subscription.cursor < size) {
                    event = channel.history.get(subscription.cursor);
                    subscription.cursor++;
                } else if (channel.complete) {
                    subscription.cancelled = true;
                    subscription.scheduled = false;
                    channel.subscriptions.remove(subscription);
                    complete = true;
                } else {
                    subscription.scheduled = false;
                    return;
                }
            }
            if (dropped) {
                subscription.subscriber.onDropped();
                return;
            }
            if (event != null) {
                subscription.subscriber.onEvent(event);
            }
            if (complete) {
                subscription.subscriber.onComplete();
                return;
            }
        }
    }
}

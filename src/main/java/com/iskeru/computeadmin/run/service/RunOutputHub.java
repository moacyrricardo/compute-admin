package com.iskeru.computeadmin.run.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-run output fan-out. The run task feeds stdout/stderr chunks and a final exit
 * here as they arrive; this hub broadcasts each to every live subscriber (the SSE
 * output endpoint, and later the MCP progress consumer of spec 008) and buffers the
 * full history so a <strong>late subscriber</strong> receives the buffered prefix
 * and then the live tail — with no lost chunk and no duplication, because replay
 * and registration happen under one per-run lock.
 *
 * <p>This is the streaming half of the engine; persistence of the same output onto
 * the append-only {@code Run} is the {@code RunService}'s concern. State is kept in
 * memory only (it is a live view, not the record of truth) and dropped when the run
 * completes and its last subscriber drains.
 *
 * <p>spec-005.
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
    }

    /** Mutable per-run state, guarded by its own monitor. */
    private static final class Channel {
        private final List<OutputEvent> history = new ArrayList<>();
        private final List<Subscriber> subscribers = new ArrayList<>();
        private boolean complete;
    }

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    private Channel channel(String runId) {
        return channels.computeIfAbsent(runId, k -> new Channel());
    }

    /** Broadcasts an output chunk and appends it to the replay buffer. */
    public void publish(String runId, OutputEvent event) {
        Channel channel = channel(runId);
        synchronized (channel) {
            if (channel.complete) {
                return;
            }
            channel.history.add(event);
            for (Subscriber subscriber : channel.subscribers) {
                subscriber.onEvent(event);
            }
        }
    }

    /**
     * Marks the run complete: emits a terminal {@code exit} event, notifies every
     * subscriber, and releases them. Idempotent.
     */
    public void complete(String runId, int exitCode) {
        Channel channel = channel(runId);
        synchronized (channel) {
            if (channel.complete) {
                return;
            }
            channel.complete = true;
            OutputEvent exit = new OutputEvent(OutputEvent.EXIT, String.valueOf(exitCode));
            channel.history.add(exit);
            for (Subscriber subscriber : channel.subscribers) {
                subscriber.onEvent(exit);
                subscriber.onComplete();
            }
            channel.subscribers.clear();
        }
        // The channel (with its full history) is intentionally retained so a
        // subscriber connecting after completion still replays the buffered output
        // and the exit event. In-memory only; acceptable for a single local
        // instance (retention/eviction is a v-next concern alongside S7).
    }

    /**
     * Subscribes to a run's output: replays the buffered prefix, then attaches for
     * the live tail. If the run has already completed, the full history (including
     * the {@code exit} event) is replayed and {@link Subscriber#onComplete()} is
     * called before returning — the subscriber is never registered.
     */
    public void subscribe(String runId, Subscriber subscriber) {
        Channel channel = channel(runId);
        synchronized (channel) {
            for (OutputEvent event : channel.history) {
                subscriber.onEvent(event);
            }
            if (channel.complete) {
                subscriber.onComplete();
                return;
            }
            channel.subscribers.add(subscriber);
        }
    }
}

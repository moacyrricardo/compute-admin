package com.iskeru.computeadmin.run.model;

import com.iskeru.computeadmin.common.Via;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.Action;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One execution of an approved {@link Action} against one {@link Machine} — an
 * <strong>append-only</strong> record of exactly what ran under which approved
 * definition. Deliberately <strong>not</strong> {@code @Audited}: it is already
 * immutable execution history, so it needs no {@code _aud} shadow (ARCH.md).
 *
 * <p>It captures who ran it ({@code callerUserId} + {@link Via}), the supplied
 * params snapshot ({@code paramsJson}), the exact argv executed
 * ({@code resolvedArgvJson} — sudo-prefixed, as handed to the SSH adapter), and the
 * {@code approvedSnapshotHash} that was in force at run time. That hash is asserted
 * equal to the action's live content hash before anything runs, so a run can never
 * execute a definition that drifted from what was approved (spec-005, defending the
 * spec-004 binding).
 *
 * <p>Ownership derives through {@code action.recipe.machine.owner} (spec 011); the
 * {@code run} REST surface scopes every read to the current user (a not-owned or
 * absent id reads as 404).
 *
 * <p>spec-005.
 */
@Entity
@Table(name = "run")
@Getter
@Setter
@NoArgsConstructor
public class Run {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_run_action"))
    private Action action;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "machine_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_run_machine"))
    private Machine machine;

    /** The user who ran it (from the ambient {@code CurrentUser}). */
    @Column(name = "caller_user_id", nullable = false, length = 36)
    private String callerUserId;

    /** Which transport requested the run — UI or MCP. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Via via;

    /** JSON snapshot of the supplied params ({@code {name: value}}). */
    @Column(name = "params_json", length = 4000)
    private String paramsJson;

    /** JSON array of the exact argv executed (sudo-prefixed when the action requires it). */
    @Column(name = "resolved_argv_json", nullable = false, length = 4000)
    private String resolvedArgvJson;

    /** The action's content hash in force at run time (equal to the approved hash). */
    @Column(name = "approved_snapshot_hash", nullable = false, length = 64)
    private String approvedSnapshotHash;

    /**
     * The parent fan-out run this row is a child of, or null for a standalone run
     * (the pre-022 scalar path — a fan-out of size 1 — and the parent row itself).
     * A monitor poll over a repeatable {@code (app-name, port)} list persists one
     * parent {@link Run} plus one child per item; the parent aggregates child status
     * ({@code DONE} iff all children {@code DONE}, else {@code FAILED}). Modelled as a
     * plain self-referencing id (with a DB FK) rather than an association, since a
     * child needs no eager parent graph. spec-022.
     */
    @Column(name = "parent_run_id", length = 36)
    private String parentRunId;

    /**
     * The {@code appName} label a fan-out child is tagged with (the item's app-name),
     * so the dashboard can route this child's output to the right app card. Null for a
     * parent or a non-fan-out run. Presentation metadata only — the raw stdout/stderr
     * stays exactly what the fixed template produced. spec-022.
     */
    @Column(name = "app_label", length = 64)
    private String appLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status = RunStatus.QUEUED;

    /** Process exit code; null until the run finishes. {@code -1} = no code / transport error. */
    @Column(name = "exit_code")
    private Integer exitCode;

    /** Captured stdout, appended as chunks arrive. */
    @Lob
    @Column(name = "stdout")
    private String stdout;

    /** Captured stderr, appended as chunks arrive. */
    @Lob
    @Column(name = "stderr")
    private String stderr;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}

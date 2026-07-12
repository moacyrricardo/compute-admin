package com.iskeru.computeadmin.run.repository;

import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.model.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Run}. Owner-scoped lookups traverse
 * {@code action.recipe.machine.owner.id}; a not-owned or absent id is invisible, so
 * the service turns it into a 404 (existence never leaked).
 *
 * <p>spec-005.
 */
public interface RunRepository extends JpaRepository<Run, String> {

    Optional<Run> findByIdAndAction_Recipe_Machine_Owner_Id(String id, String ownerId);

    /**
     * Every run in one of the given statuses, across all owners. Deliberately
     * owner-bypassing — the boot {@code RunReconciler} sweeps the whole fleet's
     * non-terminal rows (mirroring {@code ConnectivityCheckJob}'s {@code findAll()}
     * fleet sweep), which is safe under the single-instance invariant (spec-016).
     */
    List<Run> findByStatusIn(Collection<RunStatus> statuses);

    /**
     * The child runs of a fan-out parent (spec-022). Used to aggregate the parent's
     * status once every child is terminal.
     */
    List<Run> findByParentRunId(String parentRunId);

    // --- run-row pruning (spec-022, extends spec-013 eviction) -----------------
    //
    // The deletion unit is a top-level run (a fan-out parent or a standalone run,
    // both `parent_run_id IS NULL`) together with its children. Because the parent
    // is terminal only once all children are terminal, selecting terminal top-level
    // rows never touches a QUEUED/RUNNING row or a live child. Children are deleted
    // first (the self-FK forbids deleting a parent while children reference it).

    /** Ids of terminal top-level runs whose {@code finishedAt} is before {@code cutoff}. */
    @Query("select r.id from Run r where r.parentRunId is null "
            + "and r.status in :terminal and r.finishedAt is not null and r.finishedAt < :cutoff")
    List<String> findTopLevelTerminalIdsFinishedBefore(
            @Param("terminal") Collection<RunStatus> terminal, @Param("cutoff") Instant cutoff);

    /** Action ids with more than {@code cap} terminal top-level runs (spec-022 per-action cap). */
    @Query("select r.action.id from Run r where r.parentRunId is null and r.status in :terminal "
            + "group by r.action.id having count(r) > :cap")
    List<String> findActionIdsWithTerminalTopLevelCountAbove(
            @Param("terminal") Collection<RunStatus> terminal, @Param("cap") long cap);

    /** Terminal top-level run ids for one action, newest first (for cap trimming). */
    @Query("select r.id from Run r where r.action.id = :actionId and r.parentRunId is null "
            + "and r.status in :terminal order by r.createdAt desc")
    List<String> findTerminalTopLevelIdsNewestFirst(
            @Param("actionId") String actionId, @Param("terminal") Collection<RunStatus> terminal);

    @Modifying
    @Query("delete from Run r where r.parentRunId in :parentIds")
    void deleteByParentRunIdIn(@Param("parentIds") Collection<String> parentIds);

    @Modifying
    @Query("delete from Run r where r.id in :ids")
    void deleteByIdIn(@Param("ids") Collection<String> ids);
}

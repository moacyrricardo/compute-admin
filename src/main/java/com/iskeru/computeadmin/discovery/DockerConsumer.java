package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.monitor.model.Bucket;
import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.Dedication;

import java.util.List;

/**
 * One docker-sourced <strong>consumer</strong> a {@link RecipeDiscoverer} classifies for
 * the spec-032 consumer contract: a compose project (an {@link ConsumerRole#APP}), a
 * datastore ({@link ConsumerRole#DATABASE}, {@link Dedication#DEDICATED} to its owning
 * project or {@link Dedication#SHARED} across apps), or the synthetic {@link
 * Bucket#DOCKER} remainder. It is the discovery-side twin of the monitor read's
 * {@code MonitorConsumerView}: {@link com.iskeru.computeadmin.discovery.service.DiscoveryService}
 * serialises the list onto the proposed recipe's un-audited {@code appPortList} JSON
 * column (no new schema, spec-033), and the monitor read reconstructs it into consumer
 * views. The three host-relative axes are intentionally absent here — there is no server
 * sampler; the browser fills {@code ram}/{@code cpu}/{@code disk} from the client-driven
 * poll (spec-029 gap, spec-032 §1).
 *
 * <p>{@code owner} is the single owning project of a {@code DEDICATED} datastore (else
 * {@code null}); {@code usedBy} lists the sharing apps of a {@code SHARED} datastore
 * (best-effort, may be empty — spec-033 Known Gaps); {@code bucket} is set only on the
 * remainder consumer, whose {@code services} are empty. {@code services} lists the
 * project's containers otherwise.
 *
 * <p>spec-033.
 */
public record DockerConsumer(String name, ConsumerRole role, Dedication dedication,
                             String owner, List<String> usedBy, Bucket bucket,
                             List<DockerService> services) {

    /** One container inside a consumer: its container name, image, and classified role. */
    public record DockerService(String name, String image, ConsumerRole role) {
    }
}

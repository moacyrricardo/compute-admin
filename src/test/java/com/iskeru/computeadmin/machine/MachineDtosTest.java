package com.iskeru.computeadmin.machine;

import com.iskeru.computeadmin.machine.api.MachineDtos.MachineView;
import com.iskeru.computeadmin.machine.api.MachineDtos.McpMachineView;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.model.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two machine views genuinely diverge (spec-028, ARCH S9): the UI
 * {@link MachineView} carries the full SSH coordinates, while the MCP
 * {@link McpMachineView} exposes {@code id/name/status/tags} only and — being a
 * distinct record — has no {@code host}/{@code port}/{@code loginUser} component
 * at all. Asserting the record shapes (not just values) keeps a future refactor
 * from silently collapsing them back into one leaky view.
 *
 * <p>spec-028.
 */
class MachineDtosTest {

    private static Machine sampleMachine() {
        Machine machine = new Machine();
        machine.setName("web-prod-1");
        machine.setHost("10.0.0.5");
        machine.setPort(2222);
        machine.setLoginUser("deploy");
        machine.setStatus(MachineStatus.ONLINE);
        machine.getTags().add(tag("prod"));
        machine.getTags().add(tag("aws"));
        return machine;
    }

    private static Tag tag(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        return tag;
    }

    @Test
    void uiView_CarriesFullInfra() {
        MachineView view = MachineView.of(sampleMachine());

        assertThat(view.name()).isEqualTo("web-prod-1");
        assertThat(view.host()).isEqualTo("10.0.0.5");
        assertThat(view.port()).isEqualTo(2222);
        assertThat(view.loginUser()).isEqualTo("deploy");
        assertThat(view.status()).isEqualTo(MachineStatus.ONLINE);
        assertThat(view.tags()).containsExactly("aws", "prod");
    }

    @Test
    void mcpView_ExposesOnlyIdNameStatusTags() {
        McpMachineView view = McpMachineView.of(sampleMachine());

        assertThat(view.name()).isEqualTo("web-prod-1");
        assertThat(view.status()).isEqualTo(MachineStatus.ONLINE);
        assertThat(view.tags()).containsExactly("aws", "prod");
    }

    @Test
    void mcpView_HasNoInfraComponents_UiViewDoes() {
        assertThat(componentNames(McpMachineView.class))
                .containsExactlyInAnyOrder("id", "name", "status", "tags")
                .doesNotContain("host", "port", "loginUser");

        assertThat(componentNames(MachineView.class))
                .contains("host", "port", "loginUser");
    }

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}

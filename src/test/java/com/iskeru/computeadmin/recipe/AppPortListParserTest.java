package com.iskeru.computeadmin.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.recipe.api.RecipeDtos.AppPortView;
import com.iskeru.computeadmin.recipe.service.AppPortListParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec-066 (BLOCKER 1): the recipe-module parser that turns a recipe's stored
 * {@code app_port_list} CLOB into {@link AppPortView} records at the {@code GET /recipes}
 * read path. Without this the machine-detail Discovery panel re-groups against an always-empty
 * {@code appPortList} and renders zero context cards on a faithful build (063 left the two-arg
 * {@code RecipeView.of} hook unused). Proves the rich context fields round-trip, the internal
 * {@code contextKey} is never surfaced, both persisted shapes (native array / docker object)
 * parse, and every malformed/blank value degrades to an empty list (061 tolerant-reader).
 */
class AppPortListParserTest {

    private final AppPortListParser parser = new AppPortListParser(new ObjectMapper());

    @Test
    void parse_ContextfulNativeArray_CarriesContextFieldsAndManagementPort() {
        String json = "[{\"appName\":\"orders\",\"port\":8080,\"runtime\":\"springboot\","
                + "\"contextKey\":\"/opt/lab/orders\",\"contextDisplay\":\"/opt/lab/orders\","
                + "\"contextScripts\":[\"start.sh\",\"stop.sh\"],"
                + "\"sourceNote\":\"app folder · discovered via port :8080 + systemd unit\","
                + "\"confidence\":\"high\",\"scriptFolder\":\"/opt/lab/orders/scripts\","
                + "\"managementPort\":9090}]";

        List<AppPortView> items = parser.parse(json);

        assertThat(items).hasSize(1);
        AppPortView v = items.get(0);
        assertThat(v.appName()).isEqualTo("orders");
        assertThat(v.port()).isEqualTo(8080);
        assertThat(v.runtime()).isEqualTo("springboot");
        assertThat(v.contextDisplay()).isEqualTo("/opt/lab/orders");
        assertThat(v.contextScripts()).containsExactly("start.sh", "stop.sh");
        assertThat(v.sourceNote()).contains("app folder");
        assertThat(v.confidence()).isEqualTo("high");
        assertThat(v.scriptFolder()).isEqualTo("/opt/lab/orders/scripts");
        assertThat(v.managementPort()).isEqualTo(9090);
    }

    @Test
    void parse_DockerObjectWrapper_ReadsNestedAppPortList() {
        String json = "{\"dockerConsumers\":[{\"name\":\"web\"}],"
                + "\"appPortList\":[{\"appName\":\"web\",\"port\":80,\"runtime\":\"docker\"}]}";

        List<AppPortView> items = parser.parse(json);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).appName()).isEqualTo("web");
        assertThat(items.get(0).port()).isEqualTo(80);
        assertThat(items.get(0).runtime()).isEqualTo("docker");
        // A docker-object item carries no context fields → defaults, never an error.
        assertThat(items.get(0).contextDisplay()).isNull();
        assertThat(items.get(0).contextScripts()).isEmpty();
    }

    @Test
    void parse_DeclaredOnlyItem_KeepsSentinelZeroPort() {
        String json = "[{\"appName\":\"batch-worker\",\"port\":0,\"runtime\":\"systemd\","
                + "\"contextDisplay\":\"/opt/lab/batch\",\"sourceNote\":\"declared app · no port\"}]";

        List<AppPortView> items = parser.parse(json);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).port()).isZero();
        assertThat(items.get(0).managementPort()).isNull();
    }

    @Test
    void parse_BareOldRow_YieldsBaseFieldsAndEmptyContext() {
        List<AppPortView> items = parser.parse("[{\"appName\":\"legacy\",\"port\":8000,\"runtime\":\"process\"}]");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).contextDisplay()).isNull();
        assertThat(items.get(0).contextScripts()).isEmpty();
        assertThat(items.get(0).sourceNote()).isNull();
        assertThat(items.get(0).confidence()).isNull();
    }

    @Test
    void parse_NullBlankOrMalformed_YieldsEmptyList() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse("{not json")).isEmpty();
        // A pre-061 bare {dockerConsumers} object with no appPortList member → empty, not error.
        assertThat(parser.parse("{\"dockerConsumers\":[]}")).isEmpty();
    }
}

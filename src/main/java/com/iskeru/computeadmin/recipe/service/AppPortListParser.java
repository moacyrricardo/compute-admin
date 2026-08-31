package com.iskeru.computeadmin.recipe.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.recipe.api.RecipeDtos.AppPortView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a recipe's stored {@code app_port_list} CLOB into the shared {@link AppPortView}
 * wire records the recipe-read surface renders (spec-066, BLOCKER 1).
 *
 * <p>This is the recipe-module counterpart of {@code MonitorService.parseAppPortList} /
 * {@code addAppPorts}: 063 widened {@link AppPortView} with the discovery-context side-data
 * but left {@code GET /recipes} mapping through the single-arg {@code RecipeView.of} (an empty
 * list), so a faithful build renders zero context cards. The parser lives here — <em>not</em>
 * reused from {@code monitor} — because the module dependency direction is {@code monitor →
 * recipe}: {@code RecipeRS} (the lower module) cannot call up into {@code monitor}, and the
 * monitor parser is {@code private}. This is the same deliberate-duplication posture 063 took
 * for the datastore token list; {@link AppPortView} already lives in {@code recipe}, so nothing
 * new crosses a boundary.
 *
 * <p>Tolerant reader (061 contract): a null/blank/malformed value, a docker-object wrapper with
 * no {@code appPortList} member, or a bare pre-063 {@code {"appName","port","runtime"}} row all
 * parse without failing — absent context keys default to {@code null}/empty. The internal
 * {@code contextKey} is deliberately not read (S9-secret, never crosses a DTO).
 *
 * <p>spec-066.
 */
@Component
public class AppPortListParser {

    private final ObjectMapper json;

    public AppPortListParser(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Parses the recipe's persisted {@code appPortList} JSON into {@link AppPortView} items.
     * Accepts both shapes the discoverers write: the native recipe's bare item array
     * ({@code [{…}]}) and the docker recipe's combined object ({@code {"dockerConsumers":…,
     * "appPortList":[…]}}). Returns an empty list for anything unparseable.
     */
    public List<AppPortView> parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        List<AppPortView> items = new ArrayList<>();
        try {
            JsonNode root = json.readTree(rawJson);
            if (root.isArray()) {
                addItems(root, items);
            } else if (root.isObject()) {
                JsonNode array = root.get("appPortList");
                if (array != null && array.isArray()) {
                    addItems(array, items);
                }
            }
        } catch (JsonProcessingException e) {
            return List.of();
        }
        return items;
    }

    private void addItems(JsonNode array, List<AppPortView> items) {
        for (JsonNode node : array) {
            JsonNode appName = node.get("appName");
            JsonNode port = node.get("port");
            if (appName != null && port != null) {
                items.add(new AppPortView(appName.asText(), port.asInt(), text(node.get("runtime")),
                        text(node.get("contextDisplay")), stringList(node.get("contextScripts")),
                        text(node.get("sourceNote")), text(node.get("confidence")),
                        text(node.get("scriptFolder")), intOrNull(node.get("managementPort"))));
            }
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : node) {
            if (e != null && !e.isNull()) {
                out.add(e.asText());
            }
        }
        return out;
    }
}

package com.iskeru.computeadmin.recipe;

import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.recipe.service.ParamValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parameter binding (spec-004) — the runtime half of the parameter gate (S4).
 * Accept/reject per kind (ALLOWED_SET / REGEX / INT_RANGE), argv ordering, the
 * {@code sudo -n} prefix, and rejection of missing values. Pure unit test:
 * in-memory entities, no persistence.
 *
 * <p>spec-004.
 */
class ParamBinderTest {

    private final ParamBinder binder = new ParamBinder();

    @Test
    void bind_AllowedSetMember_BindsInArgvOrderWithSudoPrefix() {
        Action action = restartAction(true);

        List<String> argv = binder.bind(action, Map.of("svc", "nginx"));

        assertThat(argv).containsExactly("sudo", "-n", "systemctl", "restart", "nginx");
    }

    @Test
    void bind_WithoutSudo_OmitsThePrefix() {
        Action action = restartAction(false);

        List<String> argv = binder.bind(action, Map.of("svc", "docker"));

        assertThat(argv).containsExactly("systemctl", "restart", "docker");
    }

    @Test
    void bind_AllowedSetNonMember_IsRejected() {
        Action action = restartAction(false);

        assertThatThrownBy(() -> binder.bind(action, Map.of("svc", "evil")))
                .isInstanceOf(ParamValidationException.class);
    }

    @Test
    void bind_MissingRequiredValue_IsRejected() {
        Action action = restartAction(false);

        assertThatThrownBy(() -> binder.bind(action, Map.of()))
                .isInstanceOf(ParamValidationException.class);
    }

    @Test
    void bind_RegexMatch_IsAccepted() {
        Action action = action(false,
                List.of(literal("echo"), param("word")),
                List.of(regexDef("word", "[a-z]+")));

        assertThat(binder.bind(action, Map.of("word", "hello")))
                .containsExactly("echo", "hello");
    }

    @Test
    void bind_RegexNonMatch_IsRejected() {
        Action action = action(false,
                List.of(literal("echo"), param("word")),
                List.of(regexDef("word", "[a-z]+")));

        assertThatThrownBy(() -> binder.bind(action, Map.of("word", "Has1Digit")))
                .isInstanceOf(ParamValidationException.class);
    }

    @Test
    void bind_RegexIsAnchored_RejectsPartialMatch() {
        Action action = action(false,
                List.of(literal("echo"), param("word")),
                List.of(regexDef("word", "[a-z]+")));

        // A partial match must be rejected — the pattern is anchored to the whole value.
        assertThatThrownBy(() -> binder.bind(action, Map.of("word", "abc; rm -rf /")))
                .isInstanceOf(ParamValidationException.class);
    }

    @Test
    void bind_IntInRange_IsAccepted() {
        Action action = action(false,
                List.of(literal("kill"), param("signal")),
                List.of(intRangeDef("signal", 1, 15)));

        assertThat(binder.bind(action, Map.of("signal", "9")))
                .containsExactly("kill", "9");
    }

    @Test
    void bind_IntOutOfRange_IsRejected() {
        Action action = action(false,
                List.of(literal("kill"), param("signal")),
                List.of(intRangeDef("signal", 1, 15)));

        assertThatThrownBy(() -> binder.bind(action, Map.of("signal", "42")))
                .isInstanceOf(ParamValidationException.class);
    }

    @Test
    void bind_NonInteger_IsRejected() {
        Action action = action(false,
                List.of(literal("kill"), param("signal")),
                List.of(intRangeDef("signal", 1, 15)));

        assertThatThrownBy(() -> binder.bind(action, Map.of("signal", "nine")))
                .isInstanceOf(ParamValidationException.class);
    }

    // --- APP_PORT_LIST fan-out item binding (spec-022, S4 per item) ---------

    @Test
    void bind_AppPortItem_BindsFixedTemplateAsDiscreteArgv() {
        Action action = appProbeAction();

        // One item's components bind to the fixed single-app template as DISCRETE argv
        // elements — never a concatenated or shell-quoted line.
        List<String> argv = binder.bind(action, Map.of("app-name", "orders", "port", "8080"));

        assertThat(argv).containsExactly("probe", "orders", "8080");
    }

    @Test
    void bind_AppPortItem_BadAppName_IsRejected() {
        Action action = appProbeAction();

        // A space / shell metacharacters are outside the fixed app-name charset.
        assertThatThrownBy(() -> binder.bind(action, Map.of("app-name", "orders; rm -rf /", "port", "8080")))
                .isInstanceOf(ParamValidationException.class);
    }

    @Test
    void bind_AppPortItem_PortOutOfRange_IsRejected() {
        Action action = appProbeAction();

        assertThatThrownBy(() -> binder.bind(action, Map.of("app-name", "orders", "port", "99999")))
                .isInstanceOf(ParamValidationException.class);
    }

    @Test
    void bind_AppPortItem_NonIntegerPort_IsRejected() {
        Action action = appProbeAction();

        assertThatThrownBy(() -> binder.bind(action, Map.of("app-name", "orders", "port", "eighty")))
                .isInstanceOf(ParamValidationException.class);
    }

    // --- fixtures -----------------------------------------------------------

    /**
     * A fan-out probe: a fixed single-app template referencing the {@code app-name}
     * and {@code port} components, plus one {@code APP_PORT_LIST} composite param.
     */
    private Action appProbeAction() {
        return action(false,
                List.of(literal("probe"), param("app-name"), param("port")),
                List.of(appPortListDef("apps")));
    }

    private ParamDef appPortListDef(String name) {
        ParamDef def = new ParamDef();
        def.setName(name);
        def.setKind(ParamKind.APP_PORT_LIST);
        return def;
    }

    private Action restartAction(boolean sudo) {
        return action(sudo,
                List.of(literal("systemctl"), literal("restart"), param("svc")),
                List.of(allowedSetDef("svc", "nginx", "docker", "mysql")));
    }

    private Action action(boolean sudo, List<ArgToken> tokens, List<ParamDef> defs) {
        Action action = new Action();
        action.setSudo(sudo);
        int position = 0;
        for (ArgToken token : tokens) {
            token.setPosition(position++);
            token.setAction(action);
            action.getArgTokens().add(token);
        }
        for (ParamDef def : defs) {
            def.setAction(action);
            action.getParamDefs().add(def);
        }
        return action;
    }

    private ArgToken literal(String value) {
        ArgToken token = new ArgToken();
        token.setKind(TokenKind.LITERAL);
        token.setValue(value);
        return token;
    }

    private ArgToken param(String name) {
        ArgToken token = new ArgToken();
        token.setKind(TokenKind.PARAM);
        token.setValue(name);
        return token;
    }

    private ParamDef allowedSetDef(String name, String... values) {
        ParamDef def = new ParamDef();
        def.setName(name);
        def.setKind(ParamKind.ALLOWED_SET);
        for (String value : values) {
            ParamAllowedValue allowed = new ParamAllowedValue();
            allowed.setParamDef(def);
            allowed.setValue(value);
            def.getAllowedValues().add(allowed);
        }
        return def;
    }

    private ParamDef regexDef(String name, String pattern) {
        ParamDef def = new ParamDef();
        def.setName(name);
        def.setKind(ParamKind.REGEX);
        def.setPattern(pattern);
        return def;
    }

    private ParamDef intRangeDef(String name, int min, int max) {
        ParamDef def = new ParamDef();
        def.setName(name);
        def.setKind(ParamKind.INT_RANGE);
        def.setIntMin(min);
        def.setIntMax(max);
        return def;
    }
}

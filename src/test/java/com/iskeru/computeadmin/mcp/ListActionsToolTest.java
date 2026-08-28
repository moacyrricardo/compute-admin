package com.iskeru.computeadmin.mcp;

import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S9 runtime guarantee (spec-055): {@code list_actions} must never emit a path-shaped
 * {@code LITERAL} argToken value — a CUSTOM action's pinned absolute {@code scriptPath} —
 * as a raw path; it surfaces the accepted basename instead. PARAM tokens and non-path
 * literals pass through verbatim so an agent can still build a valid {@code run_action} call.
 *
 * <p>spec-055.
 */
class ListActionsToolTest {

    @Test
    void renderTokenValue_AbsolutePathLiteral_IsReducedToItsBasename() {
        assertThat(render(TokenKind.LITERAL, "/opt/lab/orders/deploy.sh")).isEqualTo("deploy.sh");
        assertThat(render(TokenKind.LITERAL, "/home/deploy/app/current/run")).isEqualTo("run");
    }

    @Test
    void renderTokenValue_TrailingSlashPath_IsReducedToTheLastSegment() {
        assertThat(render(TokenKind.LITERAL, "/opt/lab/orders/")).isEqualTo("orders");
    }

    @Test
    void renderTokenValue_NonPathLiteral_PassesThroughVerbatim() {
        assertThat(render(TokenKind.LITERAL, "echo")).isEqualTo("echo");
        assertThat(render(TokenKind.LITERAL, "port=\"$1\"")).isEqualTo("port=\"$1\"");
    }

    @Test
    void renderTokenValue_StandaloneDbSizingPathLiterals_AreReducedToBasenames() {
        // spec-058 relies on this 055 fix: a standalone DB-size check's path-shaped LITERALs —
        // the engine data directory and the mysql maintenance-account config — must surface only
        // their basename identity on the MCP surface, never the raw datadir or the config path (S9).
        assertThat(render(TokenKind.LITERAL, "/etc/mysql/debian.cnf")).isEqualTo("debian.cnf");
        assertThat(render(TokenKind.LITERAL, "/var/lib/postgresql/16/main")).isEqualTo("main");
        assertThat(render(TokenKind.LITERAL, "/var/lib/mysql")).isEqualTo("mysql");
    }

    @Test
    void renderTokenValue_ParamToken_PassesThroughVerbatim() {
        // A PARAM token carries a param name, not a path — never rewritten.
        assertThat(render(TokenKind.PARAM, "msg")).isEqualTo("msg");
    }

    private static String render(TokenKind kind, String value) {
        ArgToken token = new ArgToken();
        token.setKind(kind);
        token.setValue(value);
        return ListActionsTool.renderTokenValue(token);
    }
}

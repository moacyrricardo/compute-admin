package com.iskeru.computeadmin.recipe;

import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval content hash (spec-004): deterministic over the structural parts
 * (ordered argv, param rules, sudo) and independent of database id/timestamps, so
 * re-reading an action yields the same hash while any structural change changes
 * it. This is what lets the gate detect an approve-then-mutate.
 *
 * <p>spec-004.
 */
class ActionSnapshotTest {

    @Test
    void hash_IsStableAcrossIdAndTimestampChanges() {
        Action a = restartAction(true);
        String first = ActionSnapshot.hash(a);

        // Mutate only identity/timestamp facets — the hash must not move.
        a.setId("a-completely-different-id");
        a.setApprovedAt(Instant.now());
        a.setApprovedByUserId("someone");
        a.getArgTokens().forEach(t -> t.setId("tok-" + t.getPosition()));

        assertThat(ActionSnapshot.hash(a)).isEqualTo(first);
    }

    @Test
    void hash_IsEqualForTwoIndependentlyBuiltIdenticalActions() {
        assertThat(ActionSnapshot.hash(restartAction(true)))
                .isEqualTo(ActionSnapshot.hash(restartAction(true)));
    }

    @Test
    void hash_DiffersWhenAnArgTokenChanges() {
        String base = ActionSnapshot.hash(restartAction(true));

        Action changed = restartAction(true);
        changed.getArgTokens().stream()
                .filter(t -> t.getPosition() == 1)
                .forEach(t -> t.setValue("reload"));

        assertThat(ActionSnapshot.hash(changed)).isNotEqualTo(base);
    }

    @Test
    void hash_DiffersWhenAParamRuleChanges() {
        String base = ActionSnapshot.hash(restartAction(true));

        Action changed = action(true,
                new String[][]{{"L", "systemctl"}, {"L", "restart"}, {"P", "svc"}},
                allowedSetDef("svc", "nginx", "docker"));  // dropped "mysql"

        assertThat(ActionSnapshot.hash(changed)).isNotEqualTo(base);
    }

    @Test
    void hash_DiffersWhenSudoChanges() {
        assertThat(ActionSnapshot.hash(restartAction(true)))
                .isNotEqualTo(ActionSnapshot.hash(restartAction(false)));
    }

    @Test
    void hash_IsIndependentOfApprovedScriptHash() {
        // The content-pinning script digest (spec-015) is a sibling column, deliberately
        // NOT folded into the structural snapshot hash: the two gates stay independent, so
        // setting/changing approvedScriptHash must never move ActionSnapshot.hash.
        Action action = restartAction(true);
        String base = ActionSnapshot.hash(action);

        action.setApprovedScriptHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ActionSnapshot.hash(action)).isEqualTo(base);

        action.setApprovedScriptHash("0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(ActionSnapshot.hash(action)).isEqualTo(base);
    }

    // --- fixtures -----------------------------------------------------------

    private Action restartAction(boolean sudo) {
        return action(sudo,
                new String[][]{{"L", "systemctl"}, {"L", "restart"}, {"P", "svc"}},
                allowedSetDef("svc", "nginx", "docker", "mysql"));
    }

    private Action action(boolean sudo, String[][] tokens, ParamDef def) {
        Action action = new Action();
        action.setSudo(sudo);
        int position = 0;
        for (String[] token : tokens) {
            ArgToken t = new ArgToken();
            t.setPosition(position++);
            t.setKind("L".equals(token[0]) ? TokenKind.LITERAL : TokenKind.PARAM);
            t.setValue(token[1]);
            t.setAction(action);
            action.getArgTokens().add(t);
        }
        def.setAction(action);
        action.getParamDefs().add(def);
        return action;
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
}

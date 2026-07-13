package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Probes the SHA-256 of a {@code CUSTOM} action's wrapped script over the {@link
 * SshExecutor} port — the isolated SSH concern behind content-pinning. The digest is
 * captured at approval ({@code ApprovalService.approve}) and re-verified before every
 * run ({@code RunService.run}), so the bytes that execute are the bytes a human
 * approved: a post-approval byte-swap of the operator's own writable script is caught
 * even though the argv token (the path) is unchanged, closing the approve-then-run
 * TOCTOU window (and, with {@code sudo}, the S5 escalation vector).
 *
 * <p>Kept separate from the pure, offline {@code ActionSnapshot} content hash: that
 * hash is a function of the persisted entity and never touches the network, whereas a
 * script digest requires the box and applies only to pinned actions. The two gates
 * stay independently testable.
 *
 * <p>spec-015.
 */
@Component
public class ScriptPinService {

    private static final int SHA256_HEX_LENGTH = 64;

    private final SshExecutor ssh;

    public ScriptPinService(SshExecutor ssh) {
        this.ssh = ssh;
    }

    /**
     * SHA-256 hex of the script at {@code scriptPath} on {@code machine}, probed over
     * SSH with {@code sha256sum}. Returns empty when the script is unreadable — a
     * non-zero exit ({@code sha256sum} absent, file missing, or permission denied) or
     * output whose leading token is not a 64-char hex digest — never a silent skip:
     * the caller turns empty into a typed refusal ({@code ScriptUnreadableException} at
     * approval, {@code ScriptModifiedException} at run).
     *
     * <p>The path is passed as a discrete argv element, so the SSH adapter's POSIX
     * single-quoting keeps a path with spaces one literal argument (S4). {@code sudo}
     * uses the action's own escalation so a root-readable-only script is still hashable
     * without granting any new privilege (S5 posture).
     */
    public Optional<String> probe(Machine machine, String scriptPath, boolean sudo) {
        SshTarget target = new SshTarget(machine.getHost(), machine.getPort(), machine.getLoginUser());
        ExecResult result = ssh.exec(target, List.of("sha256sum", scriptPath), sudo);
        if (!result.succeeded()) {
            return Optional.empty();
        }
        return parseHash(result.stdout());
    }

    /** Parses the leading 64-hex token of {@code sha256sum} output ({@code <hex>  <path>}). */
    private static Optional<String> parseHash(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return Optional.empty();
        }
        String token = stdout.strip().split("\\s+", 2)[0];
        if (token.length() != SHA256_HEX_LENGTH || !isHex(token)) {
            return Optional.empty();
        }
        return Optional.of(token.toLowerCase());
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * The script a {@code CUSTOM} action wraps: the leading {@code LITERAL} argv token
     * (the action's {@code argTokens} ordered by position, first element). This is the
     * fixed absolute path bound at authoring time, never a param.
     */
    public static String scriptPath(Action action) {
        return action.getArgTokens().stream()
                .min(Comparator.comparingInt(ArgToken::getPosition))
                .map(ArgToken::getValue)
                .orElseThrow(() -> new IllegalStateException(
                        "CUSTOM action has no leading script-path token: " + action.getId()));
    }
}

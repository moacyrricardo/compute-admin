package com.iskeru.computeadmin.discovery.service;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Maps a discovery record's app-script location to the owning <strong>context</strong> —
 * the app/owner folder a record resolves to (spec-055, graduating concern 054). A pure,
 * side-effect-free helper over paths already in hand at the discovery site (no suffix,
 * {@code SlugGenerator}-style per CONTRIBUTING §6); the caller supplies any filesystem
 * knowledge (marker-file presence) as a {@link Predicate}, so this class never touches SSH.
 *
 * <p><strong>D2 — the wrapper-directory rule.</strong> From an app-script's folder, hop up
 * a bounded amount: if the folder's name is a {@link #wrapperSet() wrapper} the context is
 * its parent; a <em>second</em> hop fires only when that parent is <em>also</em> a wrapper
 * and the next-up directory carries a {@link #markerFiles() marker file} — capped at
 * <strong>2 hops</strong>. A <strong>boundary clamp</strong> never lets a hop land on a
 * top-level system root ({@link #boundaryRoots()}); git-rooted monorepos therefore group at
 * the repo root — intended.
 *
 * <p><strong>D1 — the identity key.</strong> Two contexts are the same iff their resolved
 * <em>physical</em> path is equal, so the key defaults to the physical context path; the
 * <em>logical</em> path is what the UI displays. When the physical path is a
 * {@code .../releases/...} or {@code .../versions/...} deploy (an atomic-symlink redeploy),
 * the key is <strong>promoted to the logical path</strong> so {@code current -> releases/<ts>} does
 * not fork a new context on every redeploy (the spec-015 redeploy-stability rider).
 *
 * <p>The {@link Context#key()} is treated as <strong>S9-secret</strong>: it never crosses
 * the MCP boundary as a path (only a human-accepted basename does).
 *
 * <p>spec-055.
 */
public final class ContextMapper {

    private ContextMapper() {
    }

    /** Directories whose name means "the app-script lives one level below its context". */
    private static final Set<String> WRAPPER_DIRS = Set.of(
            "scripts", "bin", "sbin", "libexec", "frontend", "backend",
            "cmd", "dist", "build", "src", "app");

    /** A marker file whose presence licenses the (capped) second wrapper hop. */
    private static final Set<String> MARKER_FILES = Set.of(
            ".git", "compose.yaml", "compose.yml", "docker-compose.yml", "package.json");

    /** Top-level system roots a hop must never land on (the boundary clamp). */
    private static final Set<String> BOUNDARY_ROOTS = Set.of(
            "/opt", "/srv", "/home", "/usr", "/var", "/");

    private static final int MAX_HOPS = 2;

    public static Set<String> wrapperSet() {
        return WRAPPER_DIRS;
    }

    public static Set<String> markerFiles() {
        return MARKER_FILES;
    }

    public static Set<String> boundaryRoots() {
        return BOUNDARY_ROOTS;
    }

    /**
     * The resolved context: the S9-secret dedup/pin {@code key} (physical, or logical when
     * promoted), the logical {@code display} path, and the {@code scriptFolder} the record
     * was mapped from.
     */
    public record Context(String key, String display, String scriptFolder) {
    }

    /**
     * Resolves the context for an app-script folder, using {@code markerPresent} to gate the
     * second wrapper hop (given a candidate directory, does it carry a marker file).
     *
     * @param scriptPath     the logical folder the app-script lives in (e.g. {@code /proc/<pid>/cwd})
     * @param realScriptPath the resolved physical path of that folder ({@code readlink -f}),
     *                       or {@code null} when it could not be resolved
     * @return the resolved {@link Context}, or {@code null} when {@code scriptPath} is blank
     */
    public static Context resolveContext(String scriptPath, String realScriptPath,
                                         Predicate<String> markerPresent) {
        String scriptFolder = normalize(scriptPath);
        if (scriptFolder == null) {
            return null;
        }
        int hops = hopCount(scriptFolder, markerPresent);
        String logicalContext = ascend(scriptFolder, hops);
        String realFolder = normalize(realScriptPath);
        String physicalContext = realFolder != null ? ascend(realFolder, hops) : logicalContext;
        String key = isDeployRelease(physicalContext) ? logicalContext : physicalContext;
        return new Context(key, logicalContext, scriptFolder);
    }

    /**
     * Convenience overload with no marker knowledge — single hop by default (the second hop
     * never fires without a confirmed marker).
     */
    public static Context resolveContext(String scriptPath, String realScriptPath) {
        return resolveContext(scriptPath, realScriptPath, dir -> false);
    }

    /** How many bounded upward hops the D2 wrapper rule takes from {@code scriptFolder}. */
    private static int hopCount(String scriptFolder, Predicate<String> markerPresent) {
        if (!WRAPPER_DIRS.contains(basename(scriptFolder))) {
            return 0;
        }
        String firstParent = parent(scriptFolder);
        if (isBoundary(firstParent)) {
            return 0; // clamp: never hop onto a top-level system root; stop at the child.
        }
        // Second hop only when the intermediate is also a wrapper and the next-up dir has a
        // marker file — and never past the 2-hop cap or onto a boundary root.
        if (MAX_HOPS >= 2 && WRAPPER_DIRS.contains(basename(firstParent))) {
            String secondParent = parent(firstParent);
            if (!isBoundary(secondParent) && markerPresent.test(secondParent)) {
                return 2;
            }
        }
        return 1;
    }

    /** Whether the physical context path is an atomic-symlink release/version deploy dir. */
    private static boolean isDeployRelease(String path) {
        return path != null && (path.contains("/releases/") || path.contains("/versions/"));
    }

    private static String ascend(String path, int hops) {
        String current = path;
        for (int i = 0; i < hops; i++) {
            current = parent(current);
        }
        return current;
    }

    /** Trim trailing slashes (keeping root {@code /}); {@code null}/blank → {@code null}. */
    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String parent(String path) {
        if (path.equals("/")) {
            return "/";
        }
        int slash = path.lastIndexOf('/');
        if (slash <= 0) {
            return "/";
        }
        return path.substring(0, slash);
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static boolean isBoundary(String path) {
        return BOUNDARY_ROOTS.contains(path);
    }
}

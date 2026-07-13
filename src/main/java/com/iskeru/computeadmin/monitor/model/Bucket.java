package com.iskeru.computeadmin.monitor.model;

/**
 * The synthetic remainder consumers that keep the host bars honest (spec-032 §5).
 * {@link #DOCKER} is the containers not classified as app or datastore; {@link
 * #SYSTEM} is the OS + other processes + free memory — the remainder to 100% of a
 * host. Both are consumers hidden by default and revealed on demand, so the
 * default view emphasises named consumers and the bars only fill to 100% once
 * buckets are shown. A named app/database consumer carries {@code bucket = null};
 * a bucket consumer carries a bucket and empty {@code services}.
 *
 * <p>spec-032; the bucket <em>values</em> are produced by spec-033/034.
 */
public enum Bucket {
    DOCKER,
    SYSTEM
}

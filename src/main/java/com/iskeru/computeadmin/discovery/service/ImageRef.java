package com.iskeru.computeadmin.discovery.service;

import java.util.List;
import java.util.Locale;

/**
 * Shared image-reference normalisation for the two classes that fingerprint a container by its
 * image: {@link DatastoreImages} (datastore classification, spec-033) and {@link ServiceCatalog}
 * (common-service catalog, spec-056/061). Both need the same rule — strip the registry host and
 * the {@code :tag}/{@code @digest} suffix, then match the remaining repository <em>path
 * segments</em> — so it lives here once and the two cannot drift. Pure helper (no suffix, no
 * state), package-private to the discoverers.
 *
 * <p>spec-061.
 */
final class ImageRef {

    private ImageRef() {
    }

    /**
     * The lowercased repository path segments of an image ref, with the {@code @digest} and
     * {@code :tag} suffixes and the registry host left in place as ordinary segments — so
     * {@code postgres:16}, {@code docker.io/library/postgres}, {@code bitnami/postgresql:15}
     * and {@code mirror.example.com/redis:7-alpine} all yield their engine segment. An empty
     * list for a null/blank ref.
     */
    static List<String> segments(String image) {
        if (image == null || image.isBlank()) {
            return List.of();
        }
        // Drop any @sha256:... digest and the :tag, leaving the registry host + repository path.
        String ref = image.trim().toLowerCase(Locale.ROOT);
        int at = ref.indexOf('@');
        if (at >= 0) {
            ref = ref.substring(0, at);
        }
        int lastSlash = ref.lastIndexOf('/');
        int colon = ref.indexOf(':', lastSlash + 1);
        if (colon >= 0) {
            ref = ref.substring(0, colon);
        }
        return List.of(ref.split("/"));
    }
}

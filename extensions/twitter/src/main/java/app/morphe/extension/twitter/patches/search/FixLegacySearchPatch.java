/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.search;

@SuppressWarnings("unused")
public class FixLegacySearchPatch {
    /**
     * Injection point.
     * X's SearchTimeline query rejects requests without query_source (GRAPHQL_VALIDATION_FAILED),
     * but the legacy search screen only sets it for some entry points.
     */
    public static String querySource(String querySource) {
        return querySource == null || querySource.isEmpty() ? "typed_query" : querySource;
    }
}

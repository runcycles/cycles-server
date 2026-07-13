package io.runcycles.protocol.data.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zips an {@code HMGET} projection (the requested field names) with its
 * positional Redis reply into a name-keyed map, so consumers read
 * {@code fields.get("tenant")} instead of {@code values.get(0)} — inserting or
 * reordering a projected field can no longer silently shift every later
 * column. Shared by the reservation repository's detail/list projections and
 * the expiry event hydration.
 *
 * <p>{@code HMGET} returns one entry per requested name with {@code null} for
 * absent fields; nulls are dropped, so a missing key (all-null reply) yields
 * an empty map, which callers already treat as "hash not found".
 */
public final class HashProjections {

    private HashProjections() {
    }

    /**
     * @param names  the field names passed to {@code HMGET}, in call order
     * @param values the positional {@code HMGET} reply ({@code null} tolerated)
     * @return name→value for every non-null reply entry; empty when
     *         {@code values} is null or every value is null
     */
    public static Map<String, String> mapHashFields(List<String> names, List<String> values) {
        if (values == null) {
            return Collections.emptyMap();
        }
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < names.size() && i < values.size(); i++) {
            String value = values.get(i);
            if (value != null) {
                fields.put(names.get(i), value);
            }
        }
        return fields;
    }
}

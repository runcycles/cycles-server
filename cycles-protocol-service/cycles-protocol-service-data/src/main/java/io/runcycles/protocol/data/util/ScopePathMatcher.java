package io.runcycles.protocol.data.util;

/** Exact segment matching for normalized Cycles scope paths. */
public final class ScopePathMatcher {

    private ScopePathMatcher() {
    }

    /**
     * Returns true when {@code scopePath} contains {@code segment} at a slash
     * boundary. The first-occurrence behavior is intentional and preserves the
     * existing reservation and balance filter contract.
     */
    public static boolean hasExactSegment(String scopePath, String segment) {
        if (scopePath == null || segment == null) return false;
        int index = scopePath.indexOf(segment);
        if (index < 0) return false;
        int end = index + segment.length();
        boolean startBoundary = index == 0 || scopePath.charAt(index - 1) == '/';
        boolean endBoundary = end == scopePath.length() || scopePath.charAt(end) == '/';
        return startBoundary && endBoundary;
    }
}

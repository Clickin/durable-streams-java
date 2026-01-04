package io.github.clickin.server.spi;

/**
 * Utility for validating Stream-Seq values according to protocol requirements.
 *
 * <p>Stream-Seq must be strictly increasing (lexicographic order) to prevent
 * race conditions and ensure write ordering within a writer scope.
 */
public final class StreamSequencer {
    
    private StreamSequencer() {}
    
    /**
     * Validates that a new sequence value is greater than the last seen value.
     *
     * @param lastSeq the previous sequence value (may be null)
     * @param newSeq the new sequence value to validate
     * @throws IllegalArgumentException if newSeq is less than or equal to lastSeq
     */
    public static void validateSequence(String lastSeq, String newSeq) {
        if (lastSeq != null && newSeq.compareTo(lastSeq) <= 0) {
            throw new IllegalArgumentException("Stream-Seq regression: " + newSeq + " <= " + lastSeq);
        }
    }
    
    /**
     * Checks if two sequence values represent a regression.
     *
     * @param current current sequence value
     * @param previous previous sequence value
     * @return true if current is less than or equal to previous, false otherwise
     */
    public static boolean isRegression(String current, String previous) {
        return previous != null && current.compareTo(previous) <= 0;
    }
}
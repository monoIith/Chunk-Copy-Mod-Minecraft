package dev.chunkcopy.replication;

/** Small invariant-bearing helper for root-versus-downstream classification. */
public final class MutationDepthTracker {
    private int depth;

    public boolean enter() {
        boolean root = depth == 0;
        depth++;
        return root;
    }

    public void leave() {
        if (depth <= 0) {
            throw new IllegalStateException("mutation depth underflow");
        }
        depth--;
    }

    public int depth() {
        return depth;
    }
}

package me.cortex.nvidium.gl.buffers;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/** Reference-counts shared sparse pages and commits only residency transitions. */
final class SparsePageTracker {
    @FunctionalInterface
    interface Committer { void commit(int firstPage, int pageCount, boolean resident); }

    private final Int2IntOpenHashMap references = new Int2IntOpenHashMap();
    private final Committer committer;

    SparsePageTracker(Committer committer) { this.committer = committer; }

    int residentPages() { return references.size(); }

    void retain(int first, int count) { update(first, count, true); }
    void release(int first, int count) { update(first, count, false); }

    private void update(int first, int count, boolean retain) {
        if (first < 0 || count <= 0 || first > Integer.MAX_VALUE - count) throw new IllegalArgumentException("Invalid sparse page range");
        if (!retain) {
            for (int page = first; page < first + count; page++) {
                if (references.get(page) <= 0) throw new IllegalStateException("Releasing an unallocated sparse page: " + page);
            }
        }
        int run = -1;
        for (int page = first; page < first + count; page++) {
            int previous = references.get(page);
            int next = previous + (retain ? 1 : -1);
            if (next == 0) references.remove(page);
            else references.put(page, next);
            boolean transition = retain ? previous == 0 : next == 0;
            if (transition && run < 0) run = page;
            if (!transition && run >= 0) {
                committer.commit(run, page - run, retain);
                run = -1;
            }
        }
        if (run >= 0) committer.commit(run, first + count - run, retain);
    }
}

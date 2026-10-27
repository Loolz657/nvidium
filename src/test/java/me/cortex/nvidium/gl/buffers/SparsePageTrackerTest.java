package me.cortex.nvidium.gl.buffers;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SparsePageTrackerTest {
    @Test
    void overlappingChunksCommitOnlyNewPagesAndReleaseOnlyTheirLastReference() {
        List<String> calls = new ArrayList<>();
        var pages = new SparsePageTracker((first, count, resident) -> calls.add(first + ":" + count + ":" + resident));
        pages.retain(10, 3);
        pages.retain(11, 3);
        pages.retain(11, 1);
        assertEquals(List.of("10:3:true", "13:1:true"), calls);
        assertEquals(4, pages.residentPages());
        pages.release(10, 3);
        pages.release(11, 3);
        assertEquals(List.of("10:3:true", "13:1:true", "10:1:false", "12:2:false"), calls);
        assertEquals(1, pages.residentPages());
        pages.release(11, 1);
        assertEquals("11:1:false", calls.getLast());
        assertEquals(0, pages.residentPages());
    }

    @Test
    void residencyRunsDoNotCrossAnAlreadyResidentPage() {
        List<String> calls = new ArrayList<>();
        var pages = new SparsePageTracker((first, count, resident) -> calls.add(first + ":" + count + ":" + resident));
        pages.retain(2, 1);
        pages.retain(0, 5);
        assertEquals(List.of("2:1:true", "0:2:true", "3:2:true"), calls);
        pages.release(0, 5);
        assertEquals(1, pages.residentPages());
        pages.release(2, 1);
        assertEquals(0, pages.residentPages());
    }

    @Test
    void invalidReleaseDoesNotPartlyReleaseTheRange() {
        List<String> calls = new ArrayList<>();
        var pages = new SparsePageTracker((first, count, resident) -> calls.add(first + ":" + count + ":" + resident));
        pages.retain(2, 1);
        assertThrows(IllegalStateException.class, () -> pages.release(2, 2));
        assertEquals(1, pages.residentPages());
        assertEquals(List.of("2:1:true"), calls);
        pages.release(2, 1);
        assertThrows(IllegalStateException.class, () -> pages.release(2, 1));
    }
}

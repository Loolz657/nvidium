package me.cortex.nvidium.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.nvidium.gl.GlFence;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.Buffer;
import me.cortex.nvidium.gl.buffers.PersistentClientMappedBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static me.cortex.nvidium.util.SegmentedManager.SIZE_LIMIT;
import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;

/** Bounded staging pool: a chunk upload burst may use spare buffers before waiting for GPU copies. */
public class UploadingBufferStream {
    private static final int MAX_STAGING_BUFFERS = 4;
    private final RenderDevice device;
    private final long capacity;
    private final List<StagingBuffer> buffers = new ArrayList<>();
    private StagingBuffer active;

    public UploadingBufferStream(RenderDevice device, long size) {
        if (size <= 0 || size >= (1L << 30)) throw new IllegalArgumentException("Invalid upload buffer capacity");
        this.device = device;
        this.capacity = size;
        this.active = addBuffer();
        TickableManager.register(this);
    }

    private StagingBuffer addBuffer() {
        var buffer = new StagingBuffer(device.createClientMappedBuffer(capacity), capacity);
        buffers.add(buffer);
        return buffer;
    }

    public long upload(Buffer target, long destOffset, long size) {
        if (size <= 0 || size > capacity) throw new IllegalArgumentException("Upload exceeds staging buffer capacity: " + size);
        if (destOffset < 0 || destOffset > target.getSize() - size) throw new IllegalArgumentException("Upload destination out of bounds");
        long address = active.allocate((int) size);
        if (address == SIZE_LIMIT) {
            // Seal outstanding copies and poll completed batches without blocking.
            tick();
            for (StagingBuffer candidate : buffers) {
                address = candidate.allocate((int) size);
                if (address != SIZE_LIMIT) {
                    active = candidate;
                    break;
                }
            }
            if (address == SIZE_LIMIT && buffers.size() < MAX_STAGING_BUFFERS) {
                active = addBuffer();
                address = active.allocate((int) size);
            }
            // The pool is bounded. If every buffer is still in flight, wait only for
            // one staging batch; later render commands may continue on the GPU.
            while (address == SIZE_LIMIT) {
                StagingBuffer candidate = buffers.stream().filter(buffer -> !buffer.frames.isEmpty()).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No upload batch available for reclamation"));
                candidate.frames.peek().fence.await();
                candidate.reclaim();
                address = candidate.allocate((int) size);
                if (address != SIZE_LIMIT) active = candidate;
            }
        }
        active.copies.add(new UploadData(target, address, destOffset, size));
        return active.buffer.addr + address;
    }

    public void commit() {
        for (StagingBuffer buffer : buffers) buffer.commit();
    }

    public void tick() {
        for (StagingBuffer buffer : buffers) {
            buffer.commit();
            if (!buffer.submitted.isEmpty()) {
                buffer.frames.add(new UploadFrame(new GlFence(), new LongArrayList(buffer.submitted)));
                buffer.submitted.clear();
            }
            buffer.reclaim();
        }
    }

    public void delete() {
        TickableManager.remove(this);
        for (StagingBuffer buffer : buffers) {
            buffer.buffer.delete();
            buffer.frames.forEach(frame -> frame.fence.free());
        }
        buffers.clear();
    }

    private static final class StagingBuffer {
        final PersistentClientMappedBuffer buffer;
        final SegmentedManager arena = new SegmentedManager();
        final Deque<UploadFrame> frames = new ArrayDeque<>();
        final LongArrayList pending = new LongArrayList();
        final LongArrayList submitted = new LongArrayList();
        final Deque<UploadData> copies = new ArrayDeque<>();
        long allocation = SIZE_LIMIT;
        long length;

        StagingBuffer(PersistentClientMappedBuffer buffer, long capacity) {
            this.buffer = buffer;
            arena.setLimit(capacity);
        }

        long allocate(int size) {
            if (allocation != SIZE_LIMIT && arena.expand(allocation, size)) {
                long address = allocation + length;
                length += size;
                return address;
            }
            long next = arena.alloc(size);
            if (next != SIZE_LIMIT) {
                allocation = next;
                length = size;
                pending.add(next);
            }
            return next;
        }

        void commit() {
            if (copies.isEmpty()) return;
            for (long address : pending) {
                glFlushMappedNamedBufferRange(buffer.getId(), address, arena.getSize(address));
                submitted.add(address);
            }
            pending.clear();
            glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
            for (UploadData entry : copies) {
                glCopyNamedBufferSubData(buffer.getId(), entry.target.getId(), entry.uploadOffset, entry.targetOffset, entry.size);
            }
            copies.clear();
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            allocation = SIZE_LIMIT;
            length = 0;
        }

        void reclaim() {
            while (!frames.isEmpty() && frames.peek().fence.signaled()) {
                UploadFrame frame = frames.pop();
                frame.allocations.forEach(arena::free);
                frame.fence.free();
            }
        }
    }

    private record UploadFrame(GlFence fence, LongArrayList allocations) {}
    private record UploadData(Buffer target, long uploadOffset, long targetOffset, long size) {}
}

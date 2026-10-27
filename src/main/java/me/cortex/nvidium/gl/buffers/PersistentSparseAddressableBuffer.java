package me.cortex.nvidium.gl.buffers;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.gl.GlObject;
import org.lwjgl.opengl.ARBSparseBuffer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCreateBuffers;
import static org.lwjgl.opengl.ARBDirectStateAccess.glNamedBufferStorage;
import static org.lwjgl.opengl.ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB;
import static org.lwjgl.opengl.GL15C.GL_READ_WRITE;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.NVShaderBufferLoad.*;

public class PersistentSparseAddressableBuffer extends GlObject implements IDeviceMappedBuffer {
    public static long alignUp(long number, long alignment) {
        long delta = number % alignment;
        return delta == 0?number: number + (alignment - delta);
    }

    public final long addr;
    public final long size;

    //The reason the page size is now 1mb is cause the nv driver doesnt defrag the sparse allocations easily
    // meaning smaller pages result in more fragmented memory and not happy for the driver
    // 1mb seems to work well
    public static final long PAGE_SIZE = 1<<20;//16

    public PersistentSparseAddressableBuffer(long size) {
        super(glCreateBuffers());
        if (!Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER) {
            throw new IllegalStateException();
        }
        this.size = alignUp(size, PAGE_SIZE);
        glNamedBufferStorage(id, size, GL_SPARSE_STORAGE_BIT_ARB);
        long[] holder = new long[1];
        glMakeNamedBufferResidentNV(id, GL_READ_WRITE);
        glGetNamedBufferParameterui64vNV(id, GL_BUFFER_GPU_ADDRESS_NV, holder);
        addr = holder[0];
        if (addr == 0) {
            throw new IllegalStateException();
        }
    }

    private static void doCommit(int buffer, long offset, long size, boolean commit) {
        GL21.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        ARBSparseBuffer.glBufferPageCommitmentARB(GL15.GL_ARRAY_BUFFER, offset, size, commit);
    }

    private final SparsePageTracker pages = new SparsePageTracker((first, count, resident) ->
            doCommit(id, PAGE_SIZE * first, PAGE_SIZE * count, resident));

    public int getPagesCommitted() {
        return pages.residentPages();
    }

    public void ensureAllocated(long addr, long size) {
        validateRange(addr, size);
        int pstart = (int) (addr/PAGE_SIZE);
        int pend   = (int) ((addr+size+PAGE_SIZE-1)/PAGE_SIZE);
        pages.retain(pstart, pend-pstart);
    }

    public void deallocate(long addr, long size) {
        validateRange(addr, size);
        int pstart = (int) (addr/PAGE_SIZE);
        int pend   = (int) ((addr+size+PAGE_SIZE-1)/PAGE_SIZE);
        pages.release(pstart, pend-pstart);
    }

    private void validateRange(long offset, long length) {
        if (offset < 0 || length <= 0 || offset > this.size - length) throw new IllegalArgumentException("Sparse buffer range out of bounds");
    }

    @Override
    public long getDeviceAddress() {
        return addr;
    }

    public void delete() {
        super.free0();
        glMakeNamedBufferNonResidentNV(id);
        glDeleteBuffers(id);
    }

    @Override
    public void free() {
        this.delete();
    }

    @Override
    public long getSize() {
        return size;
    }
}

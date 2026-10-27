package me.cortex.nvidium.gl;

import static org.lwjgl.opengl.GL32.*;

public class GlFence extends TrackedObject {
    private final long fence;
    private boolean signaled;

    public GlFence() {
        this.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    public boolean signaled() {
        this.assertNotFreed();
        if (!this.signaled) {
            int ret = glClientWaitSync(this.fence, 0, 0);
            if (ret == GL_ALREADY_SIGNALED || ret == GL_CONDITION_SATISFIED) {
                this.signaled = true;
            } else if (ret != GL_TIMEOUT_EXPIRED) {
                throw new IllegalStateException("Poll for fence failed, ret: " + ret + " glError: " + glGetError());
            }
        }
        return this.signaled;
    }

    @Override
    public void free() {
        super.free0();
        glDeleteSync(this.fence);
    }

    /** Wait only for commands preceding this fence, rather than draining the whole GPU. */
    public void await() {
        this.assertNotFreed();
        while (!this.signaled) {
            int result = glClientWaitSync(this.fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000L);
            if (result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED) this.signaled = true;
            else if (result != GL_TIMEOUT_EXPIRED) throw new IllegalStateException("Waiting for upload fence failed: " + result);
        }
    }
}

package me.cortex.nvidium.smoke;

/** Exercises real staging-buffer overflow and reuse under the actual Fabric runtime. */
final class UploadBurstCheck {
    public static void verify() {
        // Tiny staging capacity forces overflow, batch sealing and reuse within one frame.
        var target = new me.cortex.nvidium.gl.buffers.DeviceOnlyMappedBuffer(16384);
        var stream = new me.cortex.nvidium.util.UploadingBufferStream(new me.cortex.nvidium.gl.RenderDevice(), 512);
        try {
            int verified = 0;
            for (int pass = 0; pass < 3; pass++) {
                for (int i = 0; i < 40; i++) {
                    int length = 301 + i % 7;
                    long address = stream.upload(target, i * 400L, length);
                    org.lwjgl.system.MemoryUtil.memSet(address, pass * 40 + i, length);
                    verified += length;
                    if (i % 9 == 0) stream.commit();
                }
                stream.tick();
                try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    var result = stack.malloc(16384);
                    org.lwjgl.opengl.GL45C.glGetNamedBufferSubData(target.getId(), 0, result);
                    for (int i = 0; i < 40; i++) {
                        for (int j = 0; j < 301 + i % 7; j++) if ((byte)(pass * 40 + i) != result.get(i * 400 + j)) throw new IllegalStateException("Upload burst corrupted data");
                    }
                }
            }
            if (org.lwjgl.opengl.GL11C.glGetError() != 0) throw new IllegalStateException("GL error in upload burst");
            System.out.println("NVIDIUM_UPLOAD_BURST_SUCCESS copies=120 bytesVerified=" + verified);
        } finally {
            stream.delete();
            target.delete();
        }
    }
}

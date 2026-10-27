package me.cortex.nvidium.sodiumCompat.iris;

import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.NVMeshShader;

import java.util.ArrayList;

/** Compiles one complete task/mesh/fragment program, releasing intermediates on every path. */
public final class IrisMeshProgramCompiler {
    private IrisMeshProgramCompiler() {}

    /** The caller owns the returned program and must eventually delete it on the GL thread. */
    public static int compile(String name, String task, NvidiumIrisMeshAdapter.ProgramSources source) {
        int program = GL20C.glCreateProgram();
        if (program == 0) throw new IllegalStateException("Cannot create mesh program " + name);
        var shaders = new ArrayList<Integer>();
        boolean linked = false;
        try {
            shaders.add(compileStage(name + "/task", NVMeshShader.GL_TASK_SHADER_NV, task));
            shaders.add(compileStage(name + "/mesh", NVMeshShader.GL_MESH_SHADER_NV, source.mesh()));
            shaders.add(compileStage(name + "/fragment", GL20C.GL_FRAGMENT_SHADER, source.fragment()));
            for (int shader : shaders) GL20C.glAttachShader(program, shader);
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != GL20C.GL_TRUE) {
                throw new IllegalStateException("Cannot link " + name + ": " + GL20C.glGetProgramInfoLog(program));
            }
            linked = true;
            return program;
        } finally {
            for (int shader : shaders) {
                if (linked) GL20C.glDetachShader(program, shader);
                GL20C.glDeleteShader(shader);
            }
            if (!linked) GL20C.glDeleteProgram(program);
        }
    }

    private static int compileStage(String name, int stage, String source) {
        int shader = GL20C.glCreateShader(stage);
        if (shader == 0) throw new IllegalStateException("Cannot create " + name);
        boolean compiled = false;
        try {
            GL20C.glShaderSource(shader, source);
            GL20C.glCompileShader(shader);
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != GL20C.GL_TRUE) {
                throw new IllegalStateException("Cannot compile " + name + ": " + GL20C.glGetShaderInfoLog(shader));
            }
            compiled = true;
            return shader;
        } finally {
            if (!compiled) GL20C.glDeleteShader(shader);
        }
    }
}

package com.metallum.shader;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

/**
 * Builds a single std140-layout uniform buffer without hand-rolled byte
 * offsets at every call site.
 * <p>
 * Every one of {@code shadow map / bloom / fog} "the bind is wrong" reports
 * turned out, once traced, to be one of two things:
 * <ol>
 *   <li>a name mismatch between {@code BindGroupLayout.withUniform("X")}
 *       and {@code pass.setUniform("X", ...)} — that one fails loudly
 *       ("Missing uniform X") at draw time in {@code MetalRenderPass}, so
 *       it's easy to spot;</li>
 *   <li>a wrong manual byte offset inside the uniform buffer's contents
 *       — e.g. packing a {@code vec3} at a 12-byte stride instead of
 *       std140's mandatory 16-byte base alignment. This one does NOT
 *       throw. The pipeline binds fine and draws fine; the shader just
 *       reads garbage (or the next field's data) out of the misaligned
 *       slot. From the outside that looks identical to "the bind is
 *       wrong" — bloom's mip/threshold uniforms and the shadow map's
 *       light-space matrix block are exactly the kind of multi-field
 *       uniform where this bites.</li>
 * </ol>
 * This class only fixes case 2. Every {@code put...} method advances the
 * write cursor to the next std140-aligned offset for that type BEFORE
 * writing, so fields can be appended in any order without the caller
 * doing offset arithmetic. {@link #build(Supplier)} pads the final size
 * up to a multiple of 16, since std140 requires the whole block to be
 * 16-byte aligned too.
 * <p>
 * Only the alignment rules this codebase actually needs are implemented:
 * float (4, but promoted to a vec4 slot per std140's array/struct rule
 * isn't handled here — use {@link #putFloatAsVec4} if this scalar will
 * ever sit in an array), vec3/vec4 (16), and mat4 (16, stored as four
 * consecutive vec4 columns). Add more as real passes need them rather
 * than guessing ahead of time.
 */
final class Std140Buffer {
    private ByteBuffer scratch = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
    private int cursor = 0;

    private void ensureCapacity(final int endOffset) {
        if (endOffset > scratch.capacity()) {
            int newCap = Integer.highestOneBit(endOffset - 1) << 1;
            // Must stay a DIRECT buffer: Matrix4f#get(offset, ByteBuffer)
            // goes through JOML's Unsafe-based MemUtil, which writes
            // straight into the buffer's native address. Handing it a
            // heap buffer there is a straight-up invalid memory access —
            // not a Java exception, a JVM-level SIGSEGV, since Unsafe
            // doesn't know or care that a heap buffer's "address" isn't
            // one. allocateDirect here is load-bearing, not a micro-opt.
            ByteBuffer grown = ByteBuffer.allocateDirect(newCap).order(scratch.order());
            scratch.position(0).limit(cursor);
            grown.put(scratch);
            scratch = grown;
        }
    }

    private int align(final int offset, final int alignment) {
        return (offset + (alignment - 1)) & ~(alignment - 1);
    }

    /** Plain 4-byte float. Only safe outside an array — see class doc. */
    Std140Buffer putFloat(final float value) {
        int offset = align(cursor, 4);
        ensureCapacity(offset + 4);
        scratch.putFloat(offset, value);
        cursor = offset + 4;
        return this;
    }

    /** Float widened to a full 16-byte slot, for use inside a std140 array. */
    Std140Buffer putFloatAsVec4(final float value) {
        int offset = align(cursor, 16);
        ensureCapacity(offset + 16);
        scratch.putFloat(offset, value);
        cursor = offset + 16;
        return this;
    }

    Std140Buffer putVec3(final Vector3fc value) {
        // std140: vec3 aligns to 16 but only occupies 12 -- the last 4
        // bytes of the slot are padding, not the next field.
        int offset = align(cursor, 16);
        ensureCapacity(offset + 12);
        scratch.putFloat(offset, value.x());
        scratch.putFloat(offset + 4, value.y());
        scratch.putFloat(offset + 8, value.z());
        cursor = offset + 16;
        return this;
    }

    Std140Buffer putVec4(final Vector4fc value) {
        int offset = align(cursor, 16);
        ensureCapacity(offset + 16);
        scratch.putFloat(offset, value.x());
        scratch.putFloat(offset + 4, value.y());
        scratch.putFloat(offset + 8, value.z());
        scratch.putFloat(offset + 12, value.w());
        cursor = offset + 16;
        return this;
    }

    Std140Buffer putMat4(final Matrix4f value) {
        int offset = align(cursor, 16);
        ensureCapacity(offset + 64);
        value.get(offset, scratch);
        cursor = offset + 64;
        return this;
    }

    /**
     * Finalizes the buffer (padding total size to a multiple of 16, as
     * std140 requires for the whole block) and uploads it as a
     * {@code USAGE_UNIFORM} GPU buffer. Caller owns the returned buffer
     * and must close it once the frame that reads it has finished
     * recording — same lifetime as {@code CompositePass}'s
     * {@code projUniforms} (see its try-with-resources usage).
     */
    GpuBuffer build(final Supplier<String> label) {
        int finalSize = align(cursor, 16);
        ByteBuffer data = scratch.duplicate().order(scratch.order());
        data.position(0).limit(finalSize);
        return RenderSystem.getDevice().createBuffer(label, GpuBuffer.USAGE_UNIFORM, data);
    }
}
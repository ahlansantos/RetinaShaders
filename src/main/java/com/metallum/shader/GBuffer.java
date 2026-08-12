package com.metallum.shader;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;

/**
 * Offscreen render target that opaque terrain gets redirected into instead
 * of drawing straight to the swapchain's main target.
 * <p>
 * Deliberately built on {@link TextureTarget} — the same class vanilla uses
 * for {@code entityOutlineTarget} in {@code LevelRenderer} — instead of
 * hand-rolled {@code GpuTexture}/{@code GpuTextureView} pairs. It already
 * handles resize and gives us {@link RenderTarget#blitAndBlendToTexture}
 * for compositing back onto the main target, matching the exact pattern
 * vanilla's {@code doEntityOutline()} uses.
 * <p>
 * TRIPLE-BUFFERED: the Metal backend allows up to
 * {@code MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT} (3) frames on the GPU
 * at once. A single reused {@code scene} texture is fine for the swapchain
 * (blaze3d/the OS compositor already double/triple-buffer that), but this
 * is an intermediate resource *we* own and reuse every frame — nothing
 * guaranteed the CPU wouldn't start recording frame N+1's clear into the
 * exact same texture the GPU was still reading from during frame N's
 * composite. That write-after-read hazard is what showed up as geometry
 * "ghosting"/moving with the camera (occlusion/color sampled from the
 * wrong frame's depth). Keeping one {@link TextureTarget} per in-flight
 * slot and indexing by {@code frameIndex % SLOT_COUNT} means each frame's
 * clear/draw/composite always touches a texture the GPU is guaranteed to
 * be done with (by the time we wrap back around to the same slot, the
 * encoder has already blocked on that slot's prior submission — see
 * {@code MetalCommandEncoder#awaitSubmitCompletion}).
 * <p>
 * NOTE: this used to also own the raw/blurred hemisphere-AO render
 * targets for the SSAO pass. That stage was removed, and so were fog,
 * bloom, and the shadow map — this class now only carries the scene
 * color+depth that {@link CompositePass}'s debug_depth pass reads.
 */
public final class GBuffer implements AutoCloseable {
    private static final int SLOT_COUNT = 3;

    private final TextureTarget[] sceneTargets = new TextureTarget[SLOT_COUNT];
    private int width = -1;
    private int height = -1;
    private long frameIndex = 0;
    private int activeSlot = 0;

    public void resizeIfNeeded(final int newWidth, final int newHeight) {
        if (newWidth != this.width || newHeight != this.height) {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                if (this.sceneTargets[slot] != null) {
                    this.sceneTargets[slot].resize(newWidth, newHeight);
                } else {
                    this.sceneTargets[slot] = new TextureTarget(
                            "MetalAlloy Scene " + slot, newWidth, newHeight, true, GpuFormat.RGBA8_UNORM
                    );
                }
            }
            this.width = newWidth;
            this.height = newHeight;
        }
    }

    /**
     * Call exactly once per displayed frame, before touching
     * {@link #sceneTarget()} — advances the ring buffer and picks the
     * slot every clear/draw/composite for this frame must agree on.
     * Deliberately driven by our own frame counter rather than
     * {@code MetalCommandEncoder}'s internal submit index, since that
     * index can advance more than once per displayed frame (one submit
     * per encoder flush, not per frame) and isn't exposed publicly
     * anyway — what matters here is "don't touch the texture two
     * displayed frames were still using", and a frame-scoped counter
     * gives that directly.
     */
    public void beginFrame() {
        this.activeSlot = (int) (this.frameIndex % SLOT_COUNT);
        this.frameIndex++;
    }

    /**
     * The scene target for the CURRENT frame's slot — same texture for
     * every call between one {@link #beginFrame()} and the next. Never
     * mix slots within a frame: clear, terrain draw, and composite must
     * all read/write the one texture picked at {@link #beginFrame()}.
     */
    public TextureTarget sceneTarget() {
        return this.sceneTargets[this.activeSlot];
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    @Override
    public void close() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (this.sceneTargets[slot] != null) {
                this.sceneTargets[slot].destroyBuffers();
                this.sceneTargets[slot] = null;
            }
        }
        this.width = -1;
        this.height = -1;
        this.frameIndex = 0;
        this.activeSlot = 0;
    }
}
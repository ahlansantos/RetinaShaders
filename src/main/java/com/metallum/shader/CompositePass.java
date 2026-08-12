package com.metallum.shader;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Deferred composite step: reads the GBuffer's scene color+depth and writes
 * the result onto the main render target's color+depth.
 * <p>
 * Only ONE composite pass exists right now: {@link #runOpaque}, running
 * {@code core/debug_depth}. It fires between OPAQUE and TRANSLUCENT (see
 * the call sites in {@code LevelRendererGBufferMixin} and
 * {@code SodiumWorldRendererGBufferMixin}) — so it only ever sees opaque
 * terrain. debug_depth doesn't care, but this is exactly why the old fog
 * pass never fogged water or entities: it was wired to this same
 * opaque-only call site.
 * <p>
 * {@link #runFull} exists as the correct home for fog (and anything else
 * that needs the COMPLETE frame — translucent geometry and entities
 * included) but has NO caller yet. Wiring it needs a new mixin injection
 * point after entities finish drawing and before the frame presents —
 * intentionally not guessed at here. {@code LevelRenderer}/
 * {@code GameRenderer}'s exact post-entity call chain needs to be checked
 * against the actual mapped jar for this MC version before writing that
 * mixin target; a wrong {@code @At} target either silently fails to apply
 * (Mixin logs an error, nothing else happens) or, worse, applies to the
 * wrong instruction and corrupts the frame in a way that's hard to
 * distinguish from a binding bug. Same category of mistake as the
 * {@code Std140Buffer} heap-vs-direct SIGSEGV — verify against the real
 * target before shipping it, don't extrapolate from method names alone.
 * <p>
 * UPDATE: injection point confirmed against a decompile of
 * {@code LevelRenderer} for this MC version (26.2). {@code addMainPass}
 * builds one lambda ({@code lambda$addMainPass$0}) that runs, in order:
 * OPAQUE renderGroup -> executeSolid -> executeTranslucent ->
 * executeOutline -> TRANSLUCENT renderGroup ->
 * executeTranslucentAfterTerrain (the last statement in the lambda). So
 * {@code @At("TAIL")} on {@code lambda$addMainPass$0} is the correct,
 * verified spot — opaque terrain, translucent terrain, AND entities have
 * all drawn onto {@code main} by then. It's the same lambda name the
 * existing OPAQUE-stage mixin already targets, just a different
 * {@code @At}. This is wired up now (see
 * {@code LevelRendererGBufferMixin#metallum$compositeFull}) but
 * {@link #runFull} itself is still a no-op below — no fog pass exists
 * yet, this only proves the hook point is safe to fire from.
 */
public final class CompositePass {
    private static RenderPipeline debugDepthPipeline;
    private static GpuSampler colorSampler;
    private static GpuSampler depthSampler;

    private CompositePass() {
    }

    private static RenderPipeline debugDepthPipeline() {
        if (debugDepthPipeline == null) {
            debugDepthPipeline = buildScreenPipeline("metallum/pipeline/debug_depth", "core/debug_depth");
        }
        return debugDepthPipeline;
    }

    /**
     * Shared builder for full-screen composite passes. Every composite
     * pass shares the same {@code InSampler}/{@code DepthSampler}/
     * {@code ProjUniforms} bind group layout — see {@link #runScreenPass}
     * for where those get bound. Keep it that way: a new pass that wants
     * a different set of bindings needs its own layout AND its own
     * binding block in {@code runScreenPass}, so don't casually add
     * bindings here without adding the matching bind call below.
     */
    private static RenderPipeline buildScreenPipeline(final String location, final String fragmentPath) {
        BindGroupLayout.Builder layout = BindGroupLayout.builder()
                .withSampler("InSampler")
                .withSampler("DepthSampler")
                .withUniform("ProjUniforms", UniformType.UNIFORM_BUFFER);

        return RenderPipeline.builder()
                .withLocation(location)
                .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("metallum", fragmentPath))
                .withBindGroupLayout(layout.build())
                .withColorTargetState(0, new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                .build();
    }

    // LINEAR for color: NEAREST was causing a shimmering/"shiny" artifact
    // at distance on any effect that blends scene color toward another
    // color based on distance — a hard per-texel jump instead of a
    // smooth blend reads as glinting on distant surfaces.
    private static GpuSampler colorSampler() {
        if (colorSampler == null) {
            colorSampler = RenderSystem.getDevice().createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR,
                    1, OptionalDouble.empty()
            );
        }
        return colorSampler;
    }

    // Depth stays NEAREST — filtering depth values is meaningless
    // (interpolating two unrelated surfaces' depth produces a value that
    // belongs to neither), and any view-space reconstruction (like
    // debug_depth.fsh's) assumes an exact per-texel depth sample.
    private static GpuSampler depthSampler() {
        if (depthSampler == null) {
            depthSampler = RenderSystem.getDevice().createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST,
                    1, OptionalDouble.empty()
            );
        }
        return depthSampler;
    }

    /**
     * Builds the per-frame {@code ProjUniforms} uniform buffer: the
     * current projection matrix (forward) followed by its inverse (for
     * reconstructing view-space position from depth). See
     * {@link Std140Buffer} — offsets are computed for us instead of
     * hand-written, which is where this kind of buffer usually goes
     * subtly wrong once a third field gets added later.
     */
    private static GpuBuffer buildProjUniformBuffer(final GameRenderer gameRenderer) {
        var state = gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Matrix4f projMat = new Matrix4f(state.projectionMatrix);
        Matrix4f invProjMat = new Matrix4f(projMat).invert();

        return new Std140Buffer()
                .putMat4(projMat)
                .putMat4(invProjMat)
                .build(() -> "metallum_proj_uniforms");
    }

    /**
     * Runs a per-frame pass with the given pipeline, sampling
     * {@code scene}'s color+depth and writing onto {@code main}. This is
     * the one place bindings get pushed for a screen pass — every name
     * bound here (InSampler, DepthSampler, ProjUniforms) must match a
     * {@code with...} entry in {@link #buildScreenPipeline}'s layout, or
     * {@code MetalRenderPass#pushDescriptor} throws "Missing sampler"/
     * "Missing uniform" at draw time. That mismatch was the actual shape
     * of every "bind" failure this pass hit historically — not a Metal
     * API issue, just a name in one place and not the other.
     */
    private static void runScreenPass(final RenderPipeline pipeline, final TextureTarget scene,
                                      final RenderTarget main, final GameRenderer gameRenderer,
                                      final String passName) {
        try (GpuBuffer projUniforms = buildProjUniformBuffer(gameRenderer)) {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(
                    () -> passName,
                    main.getColorTextureView(),
                    Optional.<org.joml.Vector4fc>empty(),
                    main.getDepthTextureView(),
                    OptionalDouble.empty()
            )) {
                pass.setPipeline(pipeline);
                pass.bindTexture("InSampler", scene.getColorTextureView(), colorSampler());
                pass.bindTexture("DepthSampler", scene.getDepthTextureView(), depthSampler());
                pass.setUniform("ProjUniforms", projUniforms);
                pass.draw(3, 1, 0, 0);
            }
        }
    }

    /**
     * Reads {@code gbuffer}'s current-slot scene (opaque terrain only --
     * water/entities haven't drawn yet at this point in the frame) and
     * runs the debug_depth pass. This is the only pass wired up right
     * now — see the class doc. Called from {@link ShaderPipeline}'s
     * opaque-stage hook.
     */
    public static void runOpaque(final GBuffer gbuffer, final RenderTarget main, final GameRenderer gameRenderer) {
        TextureTarget scene = gbuffer.sceneTarget();
        runScreenPass(debugDepthPipeline(), scene, main, gameRenderer, "metallum_debug_depth_stage");
    }

    /**
     * NOT CALLED YET — no mixin invokes this. This is where a rebuilt fog
     * pass belongs: it must run on {@code main} AFTER translucent geometry
     * and entities have drawn (unlike {@link #runOpaque}, which only ever
     * sees opaque terrain). See the class doc for why the injection point
     * needs to be verified against the mapped jar before wiring this up,
     * rather than guessed.
     * <p>
     * Sampling note for whoever builds this: {@link #colorSampler()} is
     * LINEAR, which is right for smooth distance blends but can bleed
     * sky color into alpha-tested foliage edges (leaves, cutout grass)
     * if the GBuffer's color target isn't resolving cutout coverage
     * cleanly — that bleed gets visibly worse once fog blends toward it
     * at distance. Worth rendering with debugDepth on both sides of a
     * cutout edge to confirm whether coverage is clean before blaming
     * the fog math itself.
     */
    public static void runFull(final GBuffer gbuffer, final RenderTarget main, final GameRenderer gameRenderer) {
        // No-op on purpose: the injection point (LevelRendererGBufferMixin,
        // TAIL of lambda$addMainPass$0) is now wired and firing every
        // frame with the complete scene already on `main`, but there's no
        // full-scene pass to run yet (fog/etc intentionally skipped for
        // now — see class doc). This used to throw
        // UnsupportedOperationException, which would've crashed every
        // frame the moment something called it. Once a real full-scene
        // pass exists, it belongs here: `main`'s own color+depth is what
        // it should sample (not `gbuffer`, which only ever holds opaque —
        // see ChunkSectionsToRenderGBufferMixin). Grab a snapshot via
        // `main.blitAndBlendToTexture(gbuffer.sceneTarget().get*View())`
        // onto a cleared scene target first (same pattern already used
        // for LevelRenderer's entityOutlineTarget), then run a screen
        // pass reading that snapshot and writing back onto `main`,
        // same shape as runScreenPass/runOpaque above.
    }

    /**
     * Releases the cached pipelines and samplers. Must be called from
     * {@link ShaderPipeline#shutdown()}.
     */
    public static void close() {
        debugDepthPipeline = null;
        if (colorSampler != null) {
            colorSampler.close();
            colorSampler = null;
        }
        if (depthSampler != null) {
            depthSampler.close();
            depthSampler = null;
        }
    }
}
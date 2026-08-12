package com.metallum.mixin.render;

import com.metallum.shader.ShaderConfig;
import com.metallum.shader.ShaderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects the OPAQUE terrain draw (inside LevelRenderer's private
 * {@code addMainPass} lambda) into MetalAlloy's offscreen {@code GBuffer}
 * scene target instead of the swapchain's main target, then blits it back
 * before the TRANSLUCENT pass runs in the same lambda.
 * <p>
 * This step deliberately does NOT touch lighting yet — visually it should
 * be indistinguishable from vanilla/plain Metallum. The goal is proving
 * the redirect-then-composite round trip doesn't lose or corrupt anything
 * (translucent depth test, entity/feature draws on top, etc. all still
 * rely on the main target being correctly populated afterward).
 * <p>
 * The scene target is cleared fully transparent (not the sky's fog color)
 * because addSkyPass already drew the sky directly onto the main target
 * *before* addMainPass runs — {@code blitAndBlendToTexture}'s src-over
 * blend means any pixel the opaque terrain doesn't cover lets that
 * already-drawn sky show through underneath, same as vanilla.
 * <p>
 * KNOWN CAVEAT: if terrain writes partial alpha anywhere (some vanilla
 * cutout/leaves edge cases do), the blend might not be bit-identical to a
 * plain copy. First thing to check if the output looks even slightly off
 * with ShaderConfig disabled vs enabled.
 * <p>
 * NOTE: the actual redirect of where opaque terrain draws happens in
 * {@link ChunkSectionsToRenderGBufferMixin}, not here. This mixin used to
 * also set {@code RenderSystem.outputColorTextureOverride}/
 * {@code outputDepthTextureOverride} thinking that would do the redirect,
 * but {@code ChunkSectionLayerGroup.outputTarget()} never reads those
 * fields (confirmed by decompiling the mapped jar) — it always resolves
 * OPAQUE to {@code GameRenderer.mainRenderTarget()} regardless. Those
 * lines were dead code and have been removed; this mixin now only owns
 * sizing/clearing the GBuffer and running the composite.
 */
@Environment(EnvType.CLIENT)
@Mixin(LevelRenderer.class)
abstract class LevelRendererGBufferMixin {
    @Shadow
    @Final
    private GameRenderer gameRenderer;

    @Inject(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            )
    )
    private void metallum$redirectOpaqueToGBuffer(final CallbackInfo ci) {
        if (!ShaderConfig.get().enabled) {
            return;
        }

        RenderTarget main = this.gameRenderer.mainRenderTarget();
        ShaderPipeline pipeline = ShaderPipeline.get();
        pipeline.beginFrame(main.width, main.height);

        TextureTarget scene = pipeline.gbuffer().sceneTarget();
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                scene.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 0.0F), scene.getDepthTexture(), 1.0
        );
    }

    // STAGE 1: plain blit, opaque only, no fog. Runs between OPAQUE and
    // TRANSLUCENT so water/entity depth-testing against main sees the
    // right depth afterward. Fog must NOT happen here (see
    // CompositePass class doc) — that was the bug where water/entities
    // never got fogged, because this used to be the only composite call
    // and it fired before they'd even drawn.
    @Inject(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void metallum$compositeGBufferBackToMain(final CallbackInfo ci) {
        if (!ShaderConfig.get().enabled) {
            return;
        }

        RenderTarget main = this.gameRenderer.mainRenderTarget();
        ShaderPipeline.get().compositeAndPresent(main, this.gameRenderer);
    }

    // STAGE 2: fires after translucent terrain AND entities have all
    // drawn onto `main` — confirmed by decompiling this MC version's
    // LevelRenderer: addMainPass's lambda (lambda$addMainPass$0) ends
    // with executeSolid -> executeTranslucent -> executeOutline ->
    // TRANSLUCENT renderGroup -> executeTranslucentAfterTerrain, in that
    // order, with no branch after the last one. TAIL of that lambda is
    // therefore guaranteed to run once, after everything, every frame —
    // no @At(INVOKE) target/ordinal guesswork needed here, unlike the
    // STAGE 1 hook above.
    //
    // This is the injection point CompositePass#runFull's class doc has
    // been waiting on. It's wired unconditionally now; runFull itself is
    // still a no-op (see its doc) until a real full-scene pass exists,
    // so this fires every frame for free with nothing behind it yet.
    @Inject(method = "lambda$addMainPass$0", at = @At("TAIL"))
    private void metallum$compositeFull(final CallbackInfo ci) {
        if (!ShaderConfig.get().enabled) {
            return;
        }

        RenderTarget main = this.gameRenderer.mainRenderTarget();
        ShaderPipeline.get().compositeFullAndPresent(main, this.gameRenderer);
    }
}
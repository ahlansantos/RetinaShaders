package com.metallum.mixin.render;

import com.metallum.shader.ShaderConfig;
import com.metallum.shader.ShaderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * THE actual redirect hook — {@link LevelRendererGBufferMixin} sets
 * {@code RenderSystem.outputColorTextureOverride}/{@code outputDepthTextureOverride}
 * hoping they'd affect where the opaque chunk pass draws, but they don't:
 * {@code ChunkSectionsToRender.renderGroup} gets its render target from
 * {@code ChunkSectionLayerGroup.outputTarget()}, which is hardcoded —
 * {@code OPAQUE} always resolves to {@code GameRenderer.mainRenderTarget()},
 * completely ignoring those RenderSystem fields. Confirmed by disassembling
 * the mapped MC jar: {@code outputTarget()} switches only on
 * {@code TRANSLUCENT} (-> {@code LevelRenderer.translucentTarget()}) with
 * everything else, including {@code OPAQUE}, falling through to
 * {@code mainRenderTarget()}.
 * <p>
 * Net effect before this mixin: opaque terrain never touched the GBuffer
 * scene target at all. It stayed at its clear value (transparent color,
 * depth 1.0) for the entire session — which is exactly why the SSAO debug
 * views came back flat white (no real depth variance to compute occlusion
 * from) and why the composite pass's {@code gl_FragDepth} write was
 * stomping {@code main}'s real terrain depth with a uniform far value
 * right before translucents (water) render.
 * <p>
 * This redirects only the {@code OPAQUE} group's output target to the
 * GBuffer scene target. {@code TRANSLUCENT} is left completely alone —
 * water keeps rendering into vanilla's own {@code translucentTarget()} and
 * getting composited by vanilla afterward, same as always.
 */
@Environment(EnvType.CLIENT)
@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderGBufferMixin {
    @Redirect(
            method = "renderGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;outputTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"
            )
    )
    private RenderTarget metallum$redirectOpaqueOutputTarget(final ChunkSectionLayerGroup group) {
        if (group == ChunkSectionLayerGroup.OPAQUE && ShaderConfig.get().enabled) {
            RenderTarget scene = ShaderPipeline.get().gbuffer().sceneTarget();
            if (scene != null) {
                return scene;
            }
            // beginFrame() hasn't allocated the scene target yet this
            // frame (shouldn't normally happen since it's called earlier
            // in the same lambda, but don't render into a null target).
        }
        return group.outputTarget();
    }
}
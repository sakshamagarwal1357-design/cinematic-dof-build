package com.indiancalm.cinematicdof.render;

import com.indiancalm.cinematicdof.runtime.DofFrameSettings;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.OutputTarget;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

/** Blaze3D-native DOF renderer for Minecraft 1.21.11. */
public final class DoFRenderer {
    private static final int UBO_SIZE = new Std140SizeCalculator()
            .putVec4().putVec4().putVec4().putVec4().putVec4().get();

    private static final RenderPipeline DOF_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.of("cinematicdof", "pipeline/depth_of_field"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("cinematicdof", "core/depth_of_field"))
            .withSampler("SceneColor")
            .withSampler("SceneDepth")
            .withUniform("DofSettings", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();

    private static SimpleFramebuffer scratch;
    private static GpuBuffer settingsBuffer;
    private static boolean failed;
    private static String failureReason = "";

    private DoFRenderer() {}

    public static void render() {
        DofFrameSettings settings = DofFrameSettings.resolve();
        if (!settings.enabled() || settings.maxBlurPixels() <= 0.01f || failed) return;
        RenderSystem.assertOnRenderThread();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        try {
            Framebuffer main = OutputTarget.MAIN_TARGET.getFramebuffer();
            if (main == null || main.getColorAttachmentView() == null || main.getDepthAttachmentView() == null) return;
            if (main.textureWidth <= 0 || main.textureHeight <= 0) return;

            ensureScratch(main.textureWidth, main.textureHeight);
            updateSettings(settings, main.textureWidth, main.textureHeight,
                    Math.max(1.0f, client.gameRenderer.getFarPlaneDistance()));
            if (scratch == null || scratch.getColorAttachmentView() == null || settingsBuffer == null) return;

            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Cinematic DOF", scratch.getColorAttachmentView(), OptionalInt.empty())) {
                pass.setPipeline(DOF_PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.bindTexture("SceneColor", main.getColorAttachmentView(),
                        RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
                pass.bindTexture("SceneDepth", main.getDepthAttachmentView(),
                        RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                pass.setUniform("DofSettings", settingsBuffer);
                pass.draw(3, 1, 0, 0);
            }

            main.drawBlit(scratch.getColorAttachmentView());
        } catch (Throwable throwable) {
            failed = true;
            failureReason = throwable.getClass().getSimpleName() + ": " +
                    (throwable.getMessage() == null ? "unknown GPU rendering error" : throwable.getMessage());
            System.err.println("[Cinematic DOF] Blaze3D renderer disabled after error: " + failureReason);
        }
    }

    private static void ensureScratch(int width, int height) {
        if (scratch == null) {
            scratch = new SimpleFramebuffer("cinematicdof_intermediate", width, height, false);
        } else if (scratch.textureWidth != width || scratch.textureHeight != height) {
            scratch.resize(width, height);
        }
        if (settingsBuffer == null) {
            settingsBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Cinematic DOF settings",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UBO_SIZE);
        }
    }

    private static void updateSettings(DofFrameSettings s, int width, int height, float farPlane) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
                    .putVec4(width, height, s.focusDistance(), s.aperture())
                    .putVec4(s.maxBlurPixels(), 0.05f, farPlane, s.focalLengthMm())
                    .putVec4(s.focusBreathingStrength(), s.bokehBlades(), s.bokehStretchX(), s.bokehStretchY())
                    .putVec4(s.highlightBoost(), Math.max(8, Math.min(64, s.sampleCount())), s.nearQuality(), s.farQuality())
                    .putVec4(s.nearBlur() ? 1.0f : 0.0f, s.farBlur() ? 1.0f : 0.0f,
                            s.depthBleedProtection() ? 1.0f : 0.0f,
                            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1.0f : 0.0f)
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(settingsBuffer.slice(), data);
        }
    }

    public static boolean hasFailed() { return failed; }
    public static String getFailureReason() { return failureReason; }

    public static void close() {
        RenderSystem.assertOnRenderThread();
        if (scratch != null) { scratch.delete(); scratch = null; }
        if (settingsBuffer != null) { settingsBuffer.close(); settingsBuffer = null; }
        failed = false;
        failureReason = "";
    }
}

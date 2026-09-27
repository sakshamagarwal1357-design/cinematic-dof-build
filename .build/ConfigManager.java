package com.indiancalm.cinematicdof.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("cinematic-dof.json");

    private static DofConfig config = new DofConfig();

    private ConfigManager() {
    }

    public static DofConfig get() {
        return config;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            DofConfig loaded = GSON.fromJson(reader, DofConfig.class);
            if (loaded != null) {
                config = loaded;
            }
        } catch (Exception ignored) {
            config = new DofConfig();
        }

        sanitize();
    }

    public static void save() {
        sanitize();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static void sanitize() {
        config.focusDistance = clamp(config.focusDistance, 0.5f, 128.0f);
        config.aperture = clamp(config.aperture, 0.7f, 16.0f);
        config.maxBlurPixels = clamp(config.maxBlurPixels, 0.0f, 32.0f);

        config.autoFocusMaxDistance = clamp(config.autoFocusMaxDistance, 4.0f, 256.0f);
        config.autoFocusResponseSeconds = clamp(config.autoFocusResponseSeconds, 0.01f, 3.0f);
        config.rackFocusSeconds = clamp(config.rackFocusSeconds, 0.0f, 10.0f);

        config.focalLengthMm = clamp(config.focalLengthMm, 18.0f, 135.0f);
        config.focusBreathingStrength = clamp(config.focusBreathingStrength, 0.0f, 1.0f);
        config.highlightBoost = clamp(config.highlightBoost, 0.0f, 1.5f);
        config.nearQuality = clamp(config.nearQuality, 0.25f, 1.0f);
        config.farQuality = clamp(config.farQuality, 0.25f, 1.0f);

        if (config.focusMode == null) {
            config.focusMode = FocusMode.MANUAL;
        }
        if (config.timelineInterpolation == null) {
            config.timelineInterpolation = TimelineInterpolation.SMOOTH;
        }
        if (config.lensPreset == null) {
            config.lensPreset = LensPreset.CUSTOM;
        }
        if (config.bokehShape == null) {
            config.bokehShape = BokehShape.CIRCULAR;
        }
        if (config.renderQuality == null) {
            config.renderQuality = RenderQuality.HIGH;
        }
        if (config.shaderCompatibilityMode == null) {
            config.shaderCompatibilityMode = ShaderCompatibilityMode.AUTO;
        }
        if (config.timelineKeyframes == null) {
            config.timelineKeyframes = new ArrayList<>();
        }

        config.timelineKeyframes.removeIf(key -> key == null);
        for (DofKeyframe key : config.timelineKeyframes) {
            key.tick = Math.max(0, key.tick);
            key.focusDistance = clamp(key.focusDistance, 0.5f, 128.0f);
            key.aperture = clamp(key.aperture, 0.7f, 16.0f);
            key.maxBlurPixels = clamp(key.maxBlurPixels, 0.0f, 32.0f);
        }
        config.timelineKeyframes.sort(Comparator.comparingInt(key -> key.tick));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

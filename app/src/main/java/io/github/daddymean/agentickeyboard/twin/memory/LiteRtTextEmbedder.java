package io.github.daddymean.agentickeyboard.twin.memory;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * On-device EmbeddingGemma 2 via LiteRT-LM's EmbeddingEngine (Apache-2.0,
 * com.google.ai.edge.litertlm:litertlm-android:0.19.1).
 *
 * The AAR is compiled with Kotlin 2.4 metadata and Java 21 class files; this
 * repo still compiles with Kotlin 2.2.10 and Java 11/17. We therefore call the
 * public API by reflection instead of compiling against those types. The
 * constructors and methods were checked with javap against 0.19.1. The engine
 * is created lazily so unit tests never load the native library unless they
 * construct this class with a real model.
 *
 * CPU only: vendor NPU runtimes are not added. On-device latency and RAM were
 * not measured on a phone in this change.
 */
public final class LiteRtTextEmbedder implements TextEmbedder, AutoCloseable {
    private static final String ENGINE = "com.google.ai.edge.litertlm.EmbeddingEngine";
    private static final String CONFIG = "com.google.ai.edge.litertlm.EmbeddingEngineConfig";
    private static final String BACKEND = "com.google.ai.edge.litertlm.Backend";
    private static final String CPU = "com.google.ai.edge.litertlm.Backend$CPU";
    private static final String OPTIONS = "com.google.ai.edge.litertlm.EmbeddingOptions";
    private static final String INPUT_TEXT = "com.google.ai.edge.litertlm.InputData$Text";

    private final File modelFile;
    private final File cacheDir;
    private Object engine;

    public LiteRtTextEmbedder(File modelFile, File cacheDir) {
        this.modelFile = modelFile;
        this.cacheDir = cacheDir;
    }

    private synchronized Object engine() throws Exception {
        if (engine != null && isInitialized(engine)) {
            return engine;
        }
        if (engine != null) {
            closeQuietly(engine);
            engine = null;
        }
        if (cacheDir != null) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }
        Class<?> backendClass = Class.forName(BACKEND);
        Object cpu = Class.forName(CPU).getDeclaredConstructor().newInstance();
        String cachePath = cacheDir != null ? cacheDir.getAbsolutePath() : null;
        Object config = Class.forName(CONFIG)
            .getDeclaredConstructor(String.class, backendClass, backendClass, backendClass, String.class)
            .newInstance(modelFile.getAbsolutePath(), cpu, null, null, cachePath);
        Object created = Class.forName(ENGINE).getDeclaredConstructor(Class.forName(CONFIG)).newInstance(config);
        Class.forName(ENGINE).getMethod("initialize").invoke(created);
        engine = created;
        return created;
    }

    @Override
    public float[] embed(String prefixedText) {
        try {
            Object text = Class.forName(INPUT_TEXT).getDeclaredConstructor(String.class).newInstance(prefixedText);
            Object options = Class.forName(OPTIONS).getDeclaredConstructor(Boolean.class).newInstance(Boolean.TRUE);
            Method compute = Class.forName(ENGINE).getMethod("computeEmbedding", List.class, Class.forName(OPTIONS));
            Object response = compute.invoke(engine(), Collections.singletonList(text), options);
            float[] vector = (float[]) response.getClass().getMethod("getEmbedding").invoke(response);
            return vector != null ? vector.clone() : new float[0];
        } catch (Exception e) {
            throw new IllegalStateException("LiteRT-LM EmbeddingEngine failed", e);
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly(engine);
        engine = null;
    }

    private static boolean isInitialized(Object engine) throws Exception {
        Object value = engine.getClass().getMethod("isInitialized").invoke(engine);
        return Boolean.TRUE.equals(value);
    }

    private static void closeQuietly(Object engine) {
        if (engine == null) return;
        try {
            engine.getClass().getMethod("close").invoke(engine);
        } catch (Exception ignored) {
            // Best-effort; a later initialize() creates a new engine.
        }
    }
}

package io.github.daddymean.agentickeyboard.ondevice;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Gemma 4 E2B via LiteRT-LM's Engine (Apache-2.0,
 * com.google.ai.edge.litertlm:litertlm-android:0.19.1).
 *
 * The AAR is compiled with Kotlin 2.4 metadata and Java 21 class files; this
 * repo still compiles with Kotlin 2.2.10 and Java 11/17. We therefore call the
 * public API by reflection instead of compiling against those types. The
 * constructors and methods were checked with javap against 0.19.1. The engine
 * is created lazily so unit tests never load the native library unless they
 * construct this class with a real model.
 *
 * GPU first, then CPU. Vendor NPU runtimes are not added.
 */
public final class LiteRtGemmaEngine implements AutoCloseable {
    private static final String ENGINE = "com.google.ai.edge.litertlm.Engine";
    private static final String CONFIG = "com.google.ai.edge.litertlm.EngineConfig";
    private static final String BACKEND = "com.google.ai.edge.litertlm.Backend";
    private static final String GPU = "com.google.ai.edge.litertlm.Backend$GPU";
    private static final String CPU = "com.google.ai.edge.litertlm.Backend$CPU";
    private static final String CONVERSATION = "com.google.ai.edge.litertlm.Conversation";
    private static final String CONV_CONFIG = "com.google.ai.edge.litertlm.ConversationConfig";
    private static final String SAMPLER = "com.google.ai.edge.litertlm.SamplerConfig";
    private static final String MESSAGE = "com.google.ai.edge.litertlm.Message";
    private static final String CONTENTS = "com.google.ai.edge.litertlm.Contents";
    private static final String CONTENT_TEXT = "com.google.ai.edge.litertlm.Content$Text";

    private final File modelFile;
    private final File cacheDir;
    private Object engine;

    public LiteRtGemmaEngine(File modelFile, File cacheDir) {
        this.modelFile = modelFile;
        this.cacheDir = cacheDir;
    }

    public synchronized String generate(String prompt) throws Exception {
        Object live = engine();
        Object convConfig = conversationConfig();
        Object conversation = Class.forName(ENGINE)
            .getMethod("createConversation", Class.forName(CONV_CONFIG))
            .invoke(live, convConfig);
        try {
            Method send = Class.forName(CONVERSATION).getMethod("sendMessage", String.class);
            Object message = send.invoke(conversation, prompt);
            return messageText(message);
        } finally {
            closeQuietly(conversation);
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly(engine);
        engine = null;
    }

    private Object engine() throws Exception {
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
        String cachePath = cacheDir != null ? cacheDir.getAbsolutePath() : null;
        Object created = tryCreate(gpuBackend(), cachePath);
        if (created == null) {
            created = tryCreate(cpuBackend(), cachePath);
        }
        if (created == null) {
            throw new IllegalStateException("LiteRT-LM Engine failed to initialize on GPU and CPU");
        }
        engine = created;
        return created;
    }

    private Object tryCreate(Object backend, String cachePath) {
        Object candidate = null;
        try {
            Class<?> backendClass = Class.forName(BACKEND);
            Object config = Class.forName(CONFIG)
                .getDeclaredConstructor(
                    String.class,
                    backendClass,
                    backendClass,
                    backendClass,
                    Integer.class,
                    Integer.class,
                    String.class
                )
                .newInstance(modelFile.getAbsolutePath(), backend, null, null, null, null, cachePath);
            candidate = Class.forName(ENGINE).getDeclaredConstructor(Class.forName(CONFIG)).newInstance(config);
            Class.forName(ENGINE).getMethod("initialize").invoke(candidate);
            return candidate;
        } catch (Exception e) {
            closeQuietly(candidate);
            return null;
        }
    }

    private static Object conversationConfig() throws Exception {
        try {
            Object sampler = Class.forName(SAMPLER)
                .getDeclaredConstructor(int.class, double.class, double.class, int.class)
                .newInstance(10, 0.9d, 0.2d, 0);
            // ConversationConfig(systemInstruction, initialMessages, tools, samplerConfig)
            return Class.forName(CONV_CONFIG)
                .getDeclaredConstructor(
                    Class.forName(CONTENTS),
                    List.class,
                    List.class,
                    Class.forName(SAMPLER)
                )
                .newInstance(null, null, null, sampler);
        } catch (Exception ignored) {
            // Fall back to defaults if optional constructor args are non-null in this AAR.
            return Class.forName(CONV_CONFIG).getDeclaredConstructor().newInstance();
        }
    }

    private static Object gpuBackend() throws Exception {
        return Class.forName(GPU).getDeclaredConstructor().newInstance();
    }

    private static Object cpuBackend() throws Exception {
        return Class.forName(CPU).getDeclaredConstructor().newInstance();
    }

    @SuppressWarnings("unchecked")
    private static String messageText(Object message) throws Exception {
        if (message == null) return null;
        Object contents = Class.forName(MESSAGE).getMethod("getContents").invoke(message);
        if (contents == null) return message.toString();
        List<Object> parts = (List<Object>) Class.forName(CONTENTS).getMethod("getContents").invoke(contents);
        if (parts == null) return message.toString();
        List<String> texts = new ArrayList<>();
        Class<?> textClass = Class.forName(CONTENT_TEXT);
        for (Object part : parts) {
            if (textClass.isInstance(part)) {
                String text = (String) textClass.getMethod("getText").invoke(part);
                if (text != null && !text.isEmpty()) texts.add(text);
            }
        }
        if (texts.isEmpty()) return message.toString();
        StringBuilder sb = new StringBuilder();
        for (String t : texts) sb.append(t);
        return sb.toString();
    }

    private static boolean isInitialized(Object engine) throws Exception {
        Object value = engine.getClass().getMethod("isInitialized").invoke(engine);
        return Boolean.TRUE.equals(value);
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) return;
        try {
            closeable.getClass().getMethod("close").invoke(closeable);
        } catch (Exception ignored) {
            // Best-effort; a later initialize() creates a new engine.
        }
    }
}

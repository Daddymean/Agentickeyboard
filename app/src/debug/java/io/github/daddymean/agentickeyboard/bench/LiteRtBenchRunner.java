package io.github.daddymean.agentickeyboard.bench;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Debug-only timing harness for LiteRT-LM rewrite models (model bake-off).
 *
 * Calls com.google.ai.edge.litertlm:litertlm-android:0.19.1 by reflection, like
 * {@code LiteRtGemmaEngine}, because the AAR carries Kotlin 2.4 metadata. Each
 * prompt gets a fresh Engine: the LFM3 model card says one Engine per
 * conversation on LiteRT-LM up to 0.19.1 (LiteRT-LM issue #3165), and the same
 * rule is applied to Gemma so both are timed the same way. Sampler settings are
 * the app's (top-k 10, top-p 0.9, temperature 0.2, seed 0).
 *
 * Timing comes from LiteRT-LM's own BenchmarkInfo (ExperimentalFlags
 * enableBenchmark), which is switched on only while this runner works and
 * restored afterwards. Not compiled into release builds (src/debug).
 */
public final class LiteRtBenchRunner {
    private static final String P = "com.google.ai.edge.litertlm.";

    public interface Progress {
        void onResult(int index, int total, PromptResult result);
    }

    private final File modelFile;
    private final File cacheDir;
    private final boolean gpu;
    private final boolean disableThinking;

    public LiteRtBenchRunner(File modelFile, File cacheDir, boolean gpu, boolean disableThinking) {
        this.modelFile = modelFile;
        this.cacheDir = cacheDir;
        this.gpu = gpu;
        this.disableThinking = disableThinking;
    }

    public List<PromptResult> run(List<String> ids, List<String> prompts, int maxOutputTokens, Progress progress) throws Exception {
        Object flags = Class.forName(P + "ExperimentalFlags").getField("INSTANCE").get(null);
        Method getBench = flags.getClass().getMethod("getEnableBenchmark");
        Method setBench = flags.getClass().getMethod("setEnableBenchmark", boolean.class);
        boolean previous = (Boolean) getBench.invoke(flags);
        setBench.invoke(flags, true);
        List<PromptResult> out = new ArrayList<>();
        try {
            for (int i = 0; i < prompts.size(); i++) {
                PromptResult r = runOne(ids.get(i), prompts.get(i), maxOutputTokens);
                out.add(r);
                if (progress != null) progress.onResult(i, prompts.size(), r);
            }
        } finally {
            setBench.invoke(flags, previous);
        }
        return out;
    }

    private PromptResult runOne(String id, String prompt, int maxOutputTokens) {
        Object engine = null;
        Object conversation = null;
        long t0 = System.nanoTime();
        double initMs = 0;
        try {
            if (cacheDir != null) {
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            }
            engine = createEngine();
            initMs = (System.nanoTime() - t0) / 1e6;
            conversation = Class.forName(P + "Engine")
                .getMethod("createConversation", Class.forName(P + "ConversationConfig"))
                .invoke(engine, conversationConfig());
            Object thinking = disableThinking
                ? Class.forName(P + "ThinkingConfig").getDeclaredConstructor(boolean.class).newInstance(false)
                : null;
            Method send = Class.forName(P + "Conversation").getMethod(
                "sendMessage", String.class, Map.class,
                Class.forName(P + "RepetitionPenaltyConfig"), Class.forName(P + "NoRepeatNgramConfig"),
                Class.forName(P + "SuppressTokensConfig"), Integer.class,
                Class.forName(P + "ThinkingConfig"), Class.forName(P + "ResponseFormat"));
            long s = System.nanoTime();
            Object message = send.invoke(conversation, prompt, Collections.emptyMap(), null, null, null,
                maxOutputTokens, thinking, null);
            double wallMs = (System.nanoTime() - s) / 1e6;
            Object info = Class.forName(P + "Conversation").getMethod("getBenchmarkInfo").invoke(conversation);
            Class<?> bi = Class.forName(P + "BenchmarkInfo");
            double ttftS = (Double) bi.getMethod("getTimeToFirstTokenInSecond").invoke(info);
            double prefill = (Double) bi.getMethod("getLastPrefillTokensPerSecond").invoke(info);
            double decode = (Double) bi.getMethod("getLastDecodeTokensPerSecond").invoke(info);
            int decodeTokens = (Integer) bi.getMethod("getLastDecodeTokenCount").invoke(info);
            return new PromptResult(id, ttftS * 1000.0, prefill, decode, decodeTokens, wallMs, initMs,
                messageText(message), null);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return new PromptResult(id, null, null, null, null, (System.nanoTime() - t0) / 1e6, initMs, null,
                cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } finally {
            closeQuietly(conversation);
            closeQuietly(engine);
        }
    }

    private Object createEngine() throws Exception {
        Class<?> backendClass = Class.forName(P + "Backend");
        Object backend = Class.forName(P + (gpu ? "Backend$GPU" : "Backend$CPU")).getDeclaredConstructor().newInstance();
        Object config = Class.forName(P + "EngineConfig")
            .getDeclaredConstructor(String.class, backendClass, backendClass, backendClass, Integer.class, Integer.class, String.class)
            .newInstance(modelFile.getAbsolutePath(), backend, null, null, null, null,
                cacheDir != null ? cacheDir.getAbsolutePath() : null);
        Object engine = Class.forName(P + "Engine").getDeclaredConstructor(Class.forName(P + "EngineConfig")).newInstance(config);
        Class.forName(P + "Engine").getMethod("initialize").invoke(engine);
        return engine;
    }

    private static Object conversationConfig() throws Exception {
        Object sampler = Class.forName(P + "SamplerConfig")
            .getDeclaredConstructor(int.class, double.class, double.class, int.class)
            .newInstance(10, 0.9d, 0.2d, 0);
        return Class.forName(P + "ConversationConfig")
            .getDeclaredConstructor(Class.forName(P + "Contents"), List.class, List.class, Class.forName(P + "SamplerConfig"))
            .newInstance(null, null, null, sampler);
    }

    @SuppressWarnings("unchecked")
    private static String messageText(Object message) throws Exception {
        if (message == null) return null;
        Object contents = Class.forName(P + "Message").getMethod("getContents").invoke(message);
        if (contents == null) return message.toString();
        List<Object> parts = (List<Object>) Class.forName(P + "Contents").getMethod("getContents").invoke(contents);
        StringBuilder sb = new StringBuilder();
        Class<?> textClass = Class.forName(P + "Content$Text");
        if (parts != null) {
            for (Object part : parts) {
                if (textClass.isInstance(part)) {
                    String text = (String) textClass.getMethod("getText").invoke(part);
                    if (text != null) sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) return;
        try {
            closeable.getClass().getMethod("close").invoke(closeable);
        } catch (Exception ignored) {
            // Best-effort.
        }
    }
}

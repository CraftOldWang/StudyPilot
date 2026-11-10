package com.studyagent.rag.rerank;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.RerankProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CPU scorer for the quantized onnx-community/bge-reranker-v2-m3-ONNX export and its tokenizer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BgeReranker {
    private static final int CPU_BATCH_SIZE = 4;
    private static final long PAD_TOKEN_ID = 1;

    private final RerankProperties properties;
    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    // Share one inference session and bound concurrent CPU work and native buffers.
    public synchronized List<Score> score(String query, List<String> passages) {
        if (passages.isEmpty()) {
            return List.of();
        }
        try {
            load();
        } catch (IOException | OrtException | RuntimeException | LinkageError ex) {
            log.warn("BGE reranker failed to load", ex);
            throw new BusinessException(503, "BGE 重排模型加载失败，请检查模型目录和本地推理依赖");
        }
        // The attention matrices grow quadratically with input length.
        int batchSize = properties.maxInputTokens() <= 1024 ? CPU_BATCH_SIZE : 1;
        List<Score> scores = new ArrayList<>(passages.size());
        try {
            for (int from = 0; from < passages.size(); from += batchSize) {
                List<String> batch = passages.subList(from, Math.min(from + batchSize, passages.size()));
                List<Encoding> encoded = batch.stream().map(passage -> tokenizer.encode(query, passage)).toList();
                float[][] logits = scoreBatch(encoded);
                for (int i = 0; i < encoded.size(); i++) {
                    float logit = logits[i][0];
                    if (!Float.isFinite(logit)) {
                        throw new IllegalStateException("BGE logits are not finite");
                    }
                    Encoding pair = encoded.get(i);
                    scores.add(new Score(logit, pair.getIds().length, pair.exceedMaxLength()));
                }
            }
        } catch (OrtException | RuntimeException ex) {
            log.warn("BGE reranker inference failed", ex);
            throw new BusinessException(503, "BGE 重排推理失败");
        }
        return scores;
    }

    private void load() throws IOException, OrtException {
        if (session != null) {
            return;
        }
        Path directory = properties.modelDirectory();
        if (directory == null) {
            throw new IOException("Missing study-agent.rerank.model-directory");
        }
        Path modelPath = directory.resolve("model_quantized.onnx");
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException("Missing " + modelPath);
        }
        OrtEnvironment ort = OrtEnvironment.getEnvironment();
        String maxTokens = String.valueOf(properties.maxInputTokens());
        HuggingFaceTokenizer candidate = HuggingFaceTokenizer.newInstance(directory.resolve("tokenizer.json"), Map.of(
                "truncation", "LONGEST_FIRST", "padding", "false", "maxLength", maxTokens, "modelMaxLength", maxTokens));
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            session = ort.createSession(modelPath.toString(), options);
        } catch (OrtException | RuntimeException | LinkageError ex) {
            candidate.close();
            throw ex;
        }
        environment = ort;
        tokenizer = candidate;
    }

    private float[][] scoreBatch(List<Encoding> encoded) throws OrtException {
        int width = encoded.stream().mapToInt(pair -> pair.getIds().length).max().orElseThrow();
        long[][] ids = new long[encoded.size()][width];
        long[][] mask = new long[encoded.size()][width];
        for (int i = 0; i < encoded.size(); i++) {
            long[] tokens = encoded.get(i).getIds();
            System.arraycopy(tokens, 0, ids[i], 0, tokens.length);
            Arrays.fill(ids[i], tokens.length, width, PAD_TOKEN_ID);
            Arrays.fill(mask[i], 0, tokens.length, 1L);
        }
        try (var inputIds = OnnxTensor.createTensor(environment, ids);
             var attentionMask = OnnxTensor.createTensor(environment, mask);
             var output = session.run(Map.of("input_ids", inputIds, "attention_mask", attentionMask))) {
            return (float[][]) output.get("logits").orElseThrow().getValue();
        }
    }

    @PreDestroy
    synchronized void close() throws OrtException {
        if (session != null) {
            try (var currentTokenizer = tokenizer) {
                session.close();
            }
        }
    }

    public record Score(double logit, int inputTokens, boolean truncated) { }
}

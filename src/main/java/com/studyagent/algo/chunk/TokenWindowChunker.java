package com.studyagent.algo.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 使用统一 token 口径切分超长结构块，并保留原解析文本坐标。
 */
public final class TokenWindowChunker {

    public static final int CHILD_MAX_TOKENS = 800;
    public static final int CHILD_OVERLAP_TOKENS = 80;
    public static final int PARENT_MAX_TOKENS = 2400;
    public static final int PARENT_OVERLAP_TOKENS = 0;

    private final TokenCounter tokenCounter;

    public TokenWindowChunker(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    public List<ChunkSegment> childChunks(ChunkSegment segment) {
        return split(segment, CHILD_MAX_TOKENS, CHILD_OVERLAP_TOKENS);
    }

    public List<ChunkSegment> parentChunks(ChunkSegment segment) {
        return split(segment, PARENT_MAX_TOKENS, PARENT_OVERLAP_TOKENS);
    }

    public List<ChunkSegment> split(ChunkSegment segment, int maxTokens, int overlapTokens) {
        return split(segment, maxTokens, overlapTokens, false);
    }

    public List<ChunkSegment> split(String text, int maxTokens, int overlapTokens) {
        return split(new ChunkSegment(text, tokenCounter.count(text),
                new SourceLocation(0, text.length(), List.of())), maxTokens, overlapTokens);
    }

    public List<ChunkSegment> splitStructured(ChunkSegment segment, int maxTokens, int overlapTokens) {
        return split(segment, maxTokens, overlapTokens, true);
    }

    private List<ChunkSegment> split(ChunkSegment segment, int maxTokens, int overlapTokens,
            boolean preferSentenceBoundaries) {
        Objects.requireNonNull(segment, "segment");
        validateWindow(maxTokens, overlapTokens);
        String content = segment.content();
        if (content.isEmpty()) {
            return List.of();
        }

        int totalTokens = tokenCounter.count(content);
        if (totalTokens <= maxTokens) {
            return List.of(new ChunkSegment(content, totalTokens, segment.sourceLocation()));
        }

        int[] boundaries = codePointBoundaries(content);
        List<ChunkSegment> chunks = new ArrayList<>();
        int startBoundary = 0;
        while (startBoundary < boundaries.length - 1) {
            int endBoundary = findWindowEnd(content, boundaries, startBoundary, maxTokens);
            if (preferSentenceBoundaries && endBoundary < boundaries.length - 1) {
                endBoundary = sentenceBoundary(content, boundaries, startBoundary, endBoundary, overlapTokens);
            }
            int start = boundaries[startBoundary];
            int end = boundaries[endBoundary];
            String window = content.substring(start, end);
            chunks.add(new ChunkSegment(
                    window,
                    tokenCounter.count(window),
                    new SourceLocation(
                            segment.sourceLocation().startInclusive() + start,
                            segment.sourceLocation().startInclusive() + end,
                            segment.sourceLocation().headingPath()
                    )
            ));
            if (endBoundary == boundaries.length - 1) {
                break;
            }
            startBoundary = findOverlapStart(content, boundaries, startBoundary, endBoundary, overlapTokens);
        }
        return List.copyOf(chunks);
    }

    private int sentenceBoundary(String text, int[] boundaries, int start, int end, int overlapTokens) {
        for (int index = end; index > start; index--) {
            int offset = boundaries[index];
            int last = text.codePointBefore(offset);
            boolean terminator = last == '\n' || last == '。' || last == '！' || last == '？'
                    || ((last == '.' || last == '!' || last == '?')
                    && (offset == text.length() || Character.isWhitespace(text.codePointAt(offset))));
            if (terminator) {
                String prefix = text.substring(boundaries[start], offset);
                boolean onlyHeadings = prefix.lines().allMatch(line -> line.isBlank()
                        || line.stripLeading().matches("#{1,6}\\s+.*"));
                if (!onlyHeadings && tokenCounter.count(prefix) > overlapTokens) { return index; }
            }
        }
        return end;
    }

    static void validateWindow(int maxTokens, int overlapTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlapTokens must satisfy 0 <= overlap < maxTokens");
        }
    }

    private int findWindowEnd(String text, int[] boundaries, int startBoundary, int maxTokens) {
        int low = startBoundary + 1;
        int high = boundaries.length - 1;
        int best = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int tokenCount = tokenCounter.count(text.substring(boundaries[startBoundary], boundaries[middle]));
            if (tokenCount <= maxTokens) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (best < 0) {
            throw new IllegalArgumentException("maxTokens is too small for one source code point");
        }
        return best;
    }

    private int findOverlapStart(
            String text,
            int[] boundaries,
            int windowStartBoundary,
            int windowEndBoundary,
            int overlapTokens
    ) {
        if (overlapTokens == 0) {
            return windowEndBoundary;
        }
        int low = windowStartBoundary + 1;
        int high = windowEndBoundary;
        int best = windowEndBoundary;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int tokenCount = tokenCounter.count(text.substring(boundaries[middle], boundaries[windowEndBoundary]));
            if (tokenCount <= overlapTokens) {
                best = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return best;
    }

    private int[] codePointBoundaries(String text) {
        int[] boundaries = new int[text.codePointCount(0, text.length()) + 1];
        int charOffset = 0;
        for (int index = 1; index < boundaries.length; index++) {
            charOffset += Character.charCount(text.codePointAt(charOffset));
            boundaries[index] = charOffset;
        }
        return boundaries;
    }
}

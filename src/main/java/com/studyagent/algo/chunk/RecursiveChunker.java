package com.studyagent.algo.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class RecursiveChunker {

    private static final Pattern SENTENCE_END = Pattern.compile("[。！？]|[.!?](?=\\s|$)");
    private static final Pattern INLINE_MATH = Pattern.compile(
            "(?<![\\\\$])\\$(?!\\$)(?:\\\\.|[^$\\\\\\r\\n])+\\$(?!\\$)|\\\\\\([^\\r\\n]*?\\\\\\)");

    private final TokenCounter counter;
    private final TokenWindowChunker window;

    RecursiveChunker(TokenCounter counter, TokenWindowChunker window) {
        this.counter = counter;
        this.window = window;
    }

    List<ChunkSegment> split(ChunkSegment source, List<StructuredChunker.MarkdownBlock> blocks, int size, int overlap) {
        TokenWindowChunker.validateWindow(size, overlap);
        List<Unit> units = new ArrayList<>();
        for (var block : blocks) {
            var location = block.segment().sourceLocation();
            var headings = location.headingPath().isEmpty() ? source.sourceLocation().headingPath() : location.headingPath();
            int start = location.startInclusive();
            if (block.atomic()) {
                int end = start + block.segment().content().stripTrailing().length();
                units.add(new Unit(start, end, headings, true));
            } else if (block.segment().tokenCount() <= size) {
                units.add(new Unit(start, location.endExclusive(), headings, false));
            } else {
                addSentences(block.segment().content(), start, headings, size, units);
            }
        }

        String text = source.content();
        List<ChunkSegment> chunks = new ArrayList<>();
        int start = 0;
        while (start < units.size()) {
            Unit first = units.get(start);
            ChunkSegment chunk = segment(source, first, first.end());
            if (chunk.tokenCount() > size) {
                chunks.addAll(first.atomic() ? window.split(chunk, size, overlap) : window.splitStructured(chunk, size, overlap));
                start++;
                continue;
            }

            int next = start + 1;
            while (next < units.size()) {
                ChunkSegment merged = segment(source, first, units.get(next).end());
                if (merged.tokenCount() > size) {
                    break;
                }
                chunk = merged;
                next++;
            }
            chunks.add(chunk);
            start = overlapStart(text, units, start, next, size, overlap);
        }
        return List.copyOf(chunks);
    }

    private void addSentences(String text, int base, List<String> headings, int size, List<Unit> units) {
        var math = INLINE_MATH.matcher(text);
        var stops = SENTENCE_END.matcher(text);
        boolean hasMath = math.find();
        int start = 0;
        while (stops.find()) {
            int end = stops.end();
            while (hasMath && math.end() <= end) {
                hasMath = math.find();
            }
            if (!hasMath || math.start() >= end) {
                addSentence(text, start, end, base, headings, size, units);
                start = end;
            }
        }
        addSentence(text, start, text.length(), base, headings, size, units);
    }

    private void addSentence(String text, int start, int end, int base, List<String> headings, int size, List<Unit> units) {
        if (counter.count(text.substring(start, end)) > size) {
            var math = INLINE_MATH.matcher(text).region(start, end);
            while (math.find()) {
                addUnit(text, start, math.start(), base, headings, false, units);
                addUnit(text, math.start(), math.end(), base, headings, true, units);
                start = math.end();
            }
        }
        addUnit(text, start, end, base, headings, false, units);
    }

    private void addUnit(String text, int start, int end, int base, List<String> headings, boolean atomic, List<Unit> units) {
        if (!text.substring(start, end).isBlank()) {
            units.add(new Unit(base + start, base + end, headings, atomic));
        }
    }

    private int overlapStart(String text, List<Unit> units, int start, int next, int size, int overlap) {
        if (overlap == 0 || next == units.size()) {
            return next;
        }
        int previousEnd = units.get(next - 1).end();
        for (int index = start + 1; index < next; index++) {
            int candidate = units.get(index).start();
            if (counter.count(text.substring(candidate, previousEnd)) <= overlap
                    && counter.count(text.substring(candidate, units.get(next).end())) <= size) {
                return index;
            }
        }
        return next;
    }

    private ChunkSegment segment(ChunkSegment source, Unit first, int end) {
        String text = source.content().substring(first.start(), end);
        int base = source.sourceLocation().startInclusive();
        return new ChunkSegment(text, counter.count(text), new SourceLocation(base + first.start(), base + end, first.headings()));
    }

    private record Unit(int start, int end, List<String> headings, boolean atomic) {}
}

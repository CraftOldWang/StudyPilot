package com.studyagent.algo.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 Markdown 结构边界优先切分，单个超长结构块再降级为 token window。
 */
public final class StructuredChunker {

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-+*]|\\d+[.)])[ \\t]+.+$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$"
    );
    private static final Pattern HTML_TABLE_START = Pattern.compile("^\\s*<table(?:\\s|>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TABLE_END = Pattern.compile("</table>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPLAY_DOLLARS = Pattern.compile("\\$\\$");
    private static final Pattern DISPLAY_BRACKETS = Pattern.compile("\\\\\\]");

    private final TokenCounter tokenCounter;
    private final TokenWindowChunker tokenWindowChunker;
    private final RecursiveChunker recursiveChunker;

    public StructuredChunker(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.tokenWindowChunker = new TokenWindowChunker(tokenCounter);
        this.recursiveChunker = new RecursiveChunker(tokenCounter, tokenWindowChunker);
    }

    public List<ChunkSegment> childChunks(String parsedText) {
        return chunk(parsedText, TokenWindowChunker.CHILD_MAX_TOKENS, TokenWindowChunker.CHILD_OVERLAP_TOKENS);
    }

    public List<ChunkSegment> parentChunks(String parsedText) {
        return parentChunks(parsedText, TokenWindowChunker.PARENT_MAX_TOKENS);
    }

    public List<ChunkSegment> parentChunks(String parsedText, int maxTokens) {
        if (maxTokens <= 0) { throw new IllegalArgumentException("maxTokens must be positive"); }
        List<ChunkSegment> blocks = chunk(parsedText, Integer.MAX_VALUE, 0);
        List<ChunkSegment> result = new ArrayList<>();
        ChunkSegment pending = null;
        for (ChunkSegment block : blocks) {
            if (pending == null) {
                pending = block;
                continue;
            }
            boolean headingOnly = pending.content().lines()
                    .filter(line -> !line.isBlank()).allMatch(line -> HEADING.matcher(line).matches());
            boolean sameSection = pending.sourceLocation().headingPath().equals(block.sourceLocation().headingPath());
            if (sameSection || headingOnly) {
                int start = pending.sourceLocation().startInclusive();
                int end = block.sourceLocation().endExclusive();
                String content = parsedText.substring(start, end);
                ChunkSegment combined = new ChunkSegment(content, tokenCounter.count(content),
                        new SourceLocation(start, end, block.sourceLocation().headingPath()));
                if (combined.tokenCount() <= maxTokens || headingOnly) {
                    // A title travels with its body; it must not become a stand-alone retrieval unit.
                    pending = combined;
                    continue;
                }
            }
            result.addAll(tokenWindowChunker.splitStructured(pending, maxTokens, 0));
            pending = block;
        }
        if (pending != null) { result.addAll(tokenWindowChunker.splitStructured(pending, maxTokens, 0)); }
        return List.copyOf(result);
    }

    public List<ChunkSegment> chunk(String parsedText, int maxTokens, int overlapTokens) {
        List<ChunkSegment> segments = new ArrayList<>();
        for (MarkdownBlock block : scan(parsedText, false)) {
            segments.addAll(tokenWindowChunker.split(block.segment(), maxTokens, overlapTokens));
        }
        return List.copyOf(segments);
    }

    public List<ChunkSegment> recursiveChunks(String text, int maxTokens, int overlapTokens) {
        return recursiveChunks(new ChunkSegment(text, tokenCounter.count(text), new SourceLocation(0, text.length(), List.of())),
                maxTokens, overlapTokens);
    }

    public List<ChunkSegment> recursiveChunks(ChunkSegment source, int maxTokens, int overlapTokens) {
        return recursiveChunker.split(source, scan(source.content(), true), maxTokens, overlapTokens);
    }

    private List<MarkdownBlock> scan(String parsedText, boolean protectRichBlocks) {
        if (parsedText == null || parsedText.isBlank()) {
            return List.of();
        }

        List<Line> lines = lines(parsedText);
        List<Heading> headings = new ArrayList<>();
        List<MarkdownBlock> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            Line line = lines.get(index);
            if (line.text().isBlank()) {
                index++;
                continue;
            }

            Matcher heading = HEADING.matcher(line.text());
            int endIndex;
            boolean atomic = false;
            if (heading.matches()) {
                updateHeadingPath(headings, heading.group(1).length(), heading.group(2).strip());
                endIndex = index + 1;
            } else if (fenceMarker(line.text()) != null) {
                endIndex = fencedBlockEnd(lines, index, fenceMarker(line.text()));
                atomic = true;
            } else if (isTableStart(lines, index)) {
                endIndex = tableEnd(lines, index);
                atomic = true;
            } else if (protectRichBlocks && richBlockEnd(line.text()) != null) {
                endIndex = markedBlockEnd(lines, index, richBlockEnd(line.text()));
                atomic = true;
            } else if (LIST_ITEM.matcher(line.text()).matches()) {
                endIndex = listEnd(lines, index);
            } else {
                endIndex = paragraphEnd(lines, index, protectRichBlocks);
            }

            int start = line.start(), end = lines.get(endIndex - 1).end();
            String content = parsedText.substring(start, end);
            blocks.add(new MarkdownBlock(new ChunkSegment(content, tokenCounter.count(content),
                    new SourceLocation(start, end, headings.stream().map(Heading::title).toList())), atomic));
            index = endIndex;
        }
        return blocks;
    }

    private Pattern richBlockEnd(String line) {
        if (HTML_TABLE_START.matcher(line).find()) { return HTML_TABLE_END; }
        if (line.stripLeading().startsWith("$$")) { return DISPLAY_DOLLARS; }
        if (line.stripLeading().startsWith("\\[")) { return DISPLAY_BRACKETS; }
        return null;
    }

    private int markedBlockEnd(List<Line> lines, int start, Pattern marker) {
        for (int index = start; index < lines.size(); index++) {
            var match = marker.matcher(lines.get(index).text().stripLeading());
            // The first delimiter opens display math. HTML has a different closing tag.
            int from = index == start && marker != HTML_TABLE_END ? 2 : 0;
            if (match.find(from)) { return index + 1; }
        }
        return lines.size();
    }

    private void updateHeadingPath(List<Heading> path, int level, String heading) {
        while (!path.isEmpty() && path.getLast().level() >= level) {
            path.removeLast();
        }
        path.add(new Heading(level, heading));
    }

    private int fencedBlockEnd(List<Line> lines, int start, String marker) {
        for (int index = start + 1; index < lines.size(); index++) {
            if (lines.get(index).text().stripLeading().startsWith(marker)) {
                return index + 1;
            }
        }
        return lines.size();
    }

    private int tableEnd(List<Line> lines, int start) {
        int index = start + 2;
        while (index < lines.size() && !lines.get(index).text().isBlank()
                && lines.get(index).text().contains("|")) {
            index++;
        }
        return index;
    }

    private int listEnd(List<Line> lines, int start) {
        int index = start + 1;
        while (index < lines.size() && LIST_ITEM.matcher(lines.get(index).text()).matches()) {
            index++;
        }
        return index;
    }

    private int paragraphEnd(List<Line> lines, int start, boolean protectRichBlocks) {
        int index = start + 1;
        while (index < lines.size()) {
            String text = lines.get(index).text();
            if (text.isBlank() || HEADING.matcher(text).matches() || fenceMarker(text) != null
                    || LIST_ITEM.matcher(text).matches() || isTableStart(lines, index)
                    || (protectRichBlocks && richBlockEnd(text) != null)) {
                break;
            }
            index++;
        }
        return index;
    }

    private boolean isTableStart(List<Line> lines, int index) {
        return index + 1 < lines.size()
                && lines.get(index).text().contains("|")
                && TABLE_SEPARATOR.matcher(lines.get(index + 1).text()).matches();
    }

    private String fenceMarker(String line) {
        String stripped = line.stripLeading();
        if (stripped.startsWith("```")) {
            return "```";
        }
        if (stripped.startsWith("~~~")) {
            return "~~~";
        }
        return null;
    }

    private List<Line> lines(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            int contentEnd = newline < 0 ? end : newline;
            if (contentEnd > start && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            lines.add(new Line(start, end, text.substring(start, contentEnd)));
            start = end;
        }
        return lines;
    }

    private record Line(int start, int end, String text) {
    }

    private record Heading(int level, String title) {
    }

    record MarkdownBlock(ChunkSegment segment, boolean atomic) {}
}

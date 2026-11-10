package com.studyagent.ingest.parse;

import java.util.List;
import java.util.Map;

public record ParsedDocument(String text, List<Block> blocks, Map<String, byte[]> images) {

    public ParsedDocument(String text) {
        this(text, List.of(), Map.of());
    }

    public record Block(int startOffset, int endOffset, int pageIndex, List<String> headingPath,
                        String type, List<Integer> bbox, List<String> imagePaths) {
        public boolean overlaps(int start, int end) {
            return startOffset < end && endOffset > start;
        }
    }
}

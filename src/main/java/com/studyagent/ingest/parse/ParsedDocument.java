package com.studyagent.ingest.parse;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

public record ParsedDocument(String text, List<Block> blocks, Map<String, byte[]> images) {

    public ParsedDocument(String text) {
        this(text, List.of(), Map.of());
    }

    public record Block(int startOffset, int endOffset, int pageIndex, List<String> headingPath,
                        String type, List<Integer> bbox, List<String> imagePaths,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> visionErrors) {
        public boolean overlaps(int start, int end) {
            return startOffset < end && endOffset > start;
        }
    }
}

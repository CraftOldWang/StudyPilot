package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("logical_documents")
public class LogicalDocument {
    @TableId
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private Long activeDocumentId;
}

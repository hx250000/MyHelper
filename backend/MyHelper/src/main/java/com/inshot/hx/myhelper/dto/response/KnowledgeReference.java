package com.inshot.hx.myhelper.dto.response;

/** 本轮送给模型的资料出处；sectionTitle 在没有章节标题时为 null。 */
public record KnowledgeReference(String source, String sectionTitle) {
}

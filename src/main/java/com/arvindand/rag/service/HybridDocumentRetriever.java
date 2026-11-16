package com.arvindand.rag.service;

import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

/**
 * Adapts {@link HybridRetriever} to the Spring AI RAG advisor contract.
 *
 * <p>The advisor only understands {@link DocumentRetriever}, so this bridges hybrid recall (dense +
 * trigram + RRF, optionally rerank) into the retrieval-augmentation pipeline that produces answers.
 *
 * @author Arvind Menon
 */
public class HybridDocumentRetriever implements DocumentRetriever {

  private final HybridRetriever hybridRetriever;
  private final String mode;
  private final int topK;
  private final int candidates;
  private final double threshold;

  public HybridDocumentRetriever(
      HybridRetriever hybridRetriever,
      String mode,
      int topK,
      int candidates,
      double threshold) {
    this.hybridRetriever = hybridRetriever;
    this.mode = mode;
    this.topK = topK;
    this.candidates = candidates;
    this.threshold = threshold;
  }

  @Override
  public List<Document> retrieve(Query query) {
    return hybridRetriever.retrieve(query.text(), topK, candidates, mode, threshold).stream()
        .map(
            hit ->
                new Document(
                    hit.id(),
                    hit.content(),
                    Map.of(
                        "source", hit.source(),
                        "chapter", hit.chapter(),
                        "article", hit.article())))
        .toList();
  }
}
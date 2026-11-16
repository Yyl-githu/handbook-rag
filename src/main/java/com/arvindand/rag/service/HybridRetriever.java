package com.arvindand.rag.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Hybrid retrieval over the handbook knowledge base.
 *
 * <p>Three modes, each a superset of the previous one:
 *
 * <ul>
 *   <li>{@code vector} — cosine similarity only (the original behaviour)
 *   <li>{@code hybrid} — cosine recall + PostgreSQL trigram keyword recall, fused with Reciprocal
 *       Rank Fusion. Dense retrieval handles paraphrase; trigram recall handles exact terms such as
 *       "国家励志奖学金" that embeddings blur together.
 *   <li>{@code hybrid_rerank} — the fused candidates are re-scored by a cross-encoder
 *       (gte-rerank-v2) before the top-k is returned.
 * </ul>
 *
 * @author Arvind Menon
 */
@Service
public class HybridRetriever {

  private static final Logger LOG = LoggerFactory.getLogger(HybridRetriever.class);

  /** RRF damping constant; 60 is the value from the original paper. */
  private static final int RRF_K = 60;

  private final VectorStore vectorStore;
  private final JdbcTemplate jdbc;
  private final RerankClient rerankClient;

  public HybridRetriever(VectorStore vectorStore, JdbcTemplate jdbc, RerankClient rerankClient) {
    this.vectorStore = vectorStore;
    this.jdbc = jdbc;
    this.rerankClient = rerankClient;
  }

  /** One retrieval result with the evidence needed to explain why it ranked where it did. */
  public record Hit(
      String id,
      String content,
      String source,
      String chapter,
      String article,
      Double vectorScore,
      Double keywordScore,
      Integer vectorRank,
      Integer keywordRank,
      double score,
      Double rerankScore) {}

  /**
   * Retrieves candidates for a query.
   *
   * @param mode {@code vector}, {@code hybrid} or {@code hybrid_rerank}
   * @param topK number of results to return
   * @param candidates size of each recall channel before fusion
   */
  public List<Hit> retrieve(
      String query, int topK, int candidates, String mode, double threshold) {
    Map<String, Hit> byId = new LinkedHashMap<>();

    // 1. dense recall
    List<Document> vectorHits =
        vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(candidates)
                .similarityThreshold(threshold)
                .build());
    for (int i = 0; i < vectorHits.size(); i++) {
      Document doc = vectorHits.get(i);
      byId.put(
          doc.getId(),
          new Hit(
              doc.getId(),
              doc.getText(),
              stringMeta(doc, "source"),
              stringMeta(doc, "chapter"),
              stringMeta(doc, "article"),
              doc.getScore(),
              null,
              i + 1,
              null,
              0,
              null));
    }

    if (!"vector".equalsIgnoreCase(mode)) {
      // 2. keyword recall via trigram similarity (works for Chinese without a tokenizer)
      List<Map<String, Object>> keywordHits = keywordSearch(query, candidates);
      for (int i = 0; i < keywordHits.size(); i++) {
        Map<String, Object> row = keywordHits.get(i);
        String id = String.valueOf(row.get("id"));
        double kscore = ((Number) row.get("score")).doubleValue();
        Hit existing = byId.get(id);
        if (existing == null) {
          byId.put(
              id,
              new Hit(
                  id,
                  String.valueOf(row.get("content")),
                  row.get("source") == null ? "" : String.valueOf(row.get("source")),
                  "",
                  "",
                  null,
                  kscore,
                  null,
                  i + 1,
                  0,
                  null));
        } else {
          byId.put(
              id,
              new Hit(
                  existing.id(),
                  existing.content(),
                  existing.source(),
                  existing.chapter(),
                  existing.article(),
                  existing.vectorScore(),
                  kscore,
                  existing.vectorRank(),
                  i + 1,
                  0,
                  null));
        }
      }
    }

    // 3. reciprocal rank fusion
    List<Hit> fused = new ArrayList<>(byId.size());
    for (Hit hit : byId.values()) {
      double score = 0;
      if (hit.vectorRank() != null) {
        score += 1.0 / (RRF_K + hit.vectorRank());
      }
      if (hit.keywordRank() != null) {
        score += 1.0 / (RRF_K + hit.keywordRank());
      }
      fused.add(
          new Hit(
              hit.id(),
              hit.content(),
              hit.source(),
              hit.chapter(),
              hit.article(),
              hit.vectorScore(),
              hit.keywordScore(),
              hit.vectorRank(),
              hit.keywordRank(),
              score,
              null));
    }
    fused.sort(Comparator.comparingDouble(Hit::score).reversed());

    if (!"hybrid_rerank".equalsIgnoreCase(mode)) {
      return fused.subList(0, Math.min(topK, fused.size()));
    }

    // 4. cross-encoder rerank over the fused head
    int rerankDepth = Math.min(fused.size(), Math.max(topK * 2, 20));
    List<Hit> head = fused.subList(0, rerankDepth);
    List<String> texts = new ArrayList<>(head.size());
    for (Hit hit : head) {
      texts.add(hit.content().length() > 1200 ? hit.content().substring(0, 1200) : hit.content());
    }
    List<RerankClient.Scored> scored = rerankClient.rerank(query, texts, Math.min(topK, head.size()));
    if (scored.isEmpty()) {
      LOG.debug("Rerank unavailable, falling back to fused order");
      return fused.subList(0, Math.min(topK, fused.size()));
    }
    List<Hit> reranked = new ArrayList<>(scored.size());
    for (RerankClient.Scored s : scored) {
      Hit hit = head.get(s.index());
      reranked.add(
          new Hit(
              hit.id(),
              hit.content(),
              hit.source(),
              hit.chapter(),
              hit.article(),
              hit.vectorScore(),
              hit.keywordScore(),
              hit.vectorRank(),
              hit.keywordRank(),
              hit.score(),
              s.score()));
    }
    return reranked;
  }

  private List<Map<String, Object>> keywordSearch(String query, int limit) {
    try {
      jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
      return jdbc.queryForList(
          """
          SELECT id::text AS id, content, metadata->>'source' AS source,
                 word_similarity(?, content) AS score
          FROM vector_store
          WHERE word_similarity(?, content) > 0.02
          ORDER BY score DESC
          LIMIT ?
          """,
          query,
          query,
          limit);
    } catch (Exception e) {
      LOG.warn("Keyword recall failed, continuing with vector only: {}", e.getMessage());
      return List.of();
    }
  }

  private static String stringMeta(Document doc, String key) {
    Object value = doc.getMetadata().get(key);
    return value == null ? "" : value.toString();
  }
}
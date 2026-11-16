package com.arvindand.rag.controller;

import com.arvindand.rag.service.HybridRetriever;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retrieval-only endpoint used by the offline evaluation harness.
 *
 * <p>Returns the ranked documents a query retrieves, without calling the chat model. This makes it
 * possible to measure retrieval quality (hit@k, MRR) cheaply and reproducibly, independently from
 * answer generation.
 *
 * <p>{@code mode=vector} is the original dense-only baseline; {@code hybrid} adds trigram keyword
 * recall fused with RRF; {@code hybrid_rerank} additionally re-scores with a cross-encoder.
 *
 * @author Arvind Menon
 */
@RestController
@RequestMapping("/api/v2/retrieval")
public class RetrievalController {

  private final HybridRetriever retriever;

  public RetrievalController(HybridRetriever retriever) {
    this.retriever = retriever;
  }

  /** Retrieves ranked chunks for a single query. */
  @GetMapping
  public List<Map<String, Object>> retrieve(
      @RequestParam("q") String query,
      @RequestParam(name = "topK", defaultValue = "8") int topK,
      @RequestParam(name = "threshold", defaultValue = "0.3") double threshold,
      @RequestParam(name = "mode", defaultValue = "vector") String mode,
      @RequestParam(name = "candidates", defaultValue = "40") int candidates) {
    return toRows(retriever.retrieve(query, topK, candidates, mode, threshold));
  }

  /** Retrieves ranked chunks for several queries in one round trip. */
  @PostMapping("/batch")
  public List<List<Map<String, Object>>> retrieveBatch(
      @RequestBody List<String> queries,
      @RequestParam(name = "topK", defaultValue = "8") int topK,
      @RequestParam(name = "threshold", defaultValue = "0.3") double threshold,
      @RequestParam(name = "mode", defaultValue = "vector") String mode,
      @RequestParam(name = "candidates", defaultValue = "40") int candidates) {
    List<List<Map<String, Object>>> out = new ArrayList<>(queries.size());
    for (String query : queries) {
      out.add(toRows(retriever.retrieve(query, topK, candidates, mode, threshold)));
    }
    return out;
  }

  private List<Map<String, Object>> toRows(List<HybridRetriever.Hit> hits) {
    List<Map<String, Object>> rows = new ArrayList<>(hits.size());
    for (int i = 0; i < hits.size(); i++) {
      HybridRetriever.Hit hit = hits.get(i);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("rank", i + 1);
      row.put("id", hit.id());
      row.put("score", hit.score());
      row.put("source", hit.source());
      row.put("chapter", hit.chapter());
      row.put("article", hit.article());
      row.put("vectorScore", hit.vectorScore());
      row.put("keywordScore", hit.keywordScore());
      row.put("vectorRank", hit.vectorRank());
      row.put("keywordRank", hit.keywordRank());
      row.put("rerankScore", hit.rerankScore());
      row.put("text", hit.content());
      rows.add(row);
    }
    return rows;
  }
}
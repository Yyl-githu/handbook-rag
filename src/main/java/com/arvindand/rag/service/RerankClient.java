package com.arvindand.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thin client for the DashScope text-rerank API (gte-rerank-v2).
 *
 * <p>Reranking is the second stage of hybrid retrieval: cheap vector/keyword recall produces a
 * candidate list, and a cross-encoder scores each candidate against the query before the top-k is
 * handed to the chat model.
 *
 * @author Arvind Menon
 */
@Component
public class RerankClient {

  private static final Logger LOG = LoggerFactory.getLogger(RerankClient.class);

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper mapper = new ObjectMapper();
  private final boolean enabled;
  private final String apiKey;
  private final String url;
  private final String model;

  public RerankClient(
      @Value("${rag.rerank.enabled:true}") boolean enabled,
      @Value("${rag.rerank.api-key:${spring.ai.openai.api-key:}}") String apiKey,
      @Value("${rag.rerank.url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
          String url,
      @Value("${rag.rerank.model:gte-rerank-v2}") String model) {
    this.enabled = enabled;
    this.apiKey = apiKey;
    this.url = url;
    this.model = model;
  }

  /** One rerank result: the position in the supplied document list and its relevance score. */
  public record Scored(int index, double score) {}

  /**
   * Reranks candidate texts against the query.
   *
   * @return scored results ordered by relevance, or an empty list when reranking is unavailable
   */
  public List<Scored> rerank(String query, List<String> documents, int topN) {
    if (!enabled || apiKey == null || apiKey.isBlank() || documents.isEmpty()) {
      return List.of();
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", model);
      body.put("input", Map.of("query", query, "documents", documents));
      body.put("parameters", Map.of("return_documents", false, "top_n", topN));

      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(30))
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
              .build();

      HttpResponse<String> response =
          http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.warn("Rerank failed with HTTP {}: {}", response.statusCode(), truncate(response.body()));
        return List.of();
      }
      JsonNode results = mapper.readTree(response.body()).path("output").path("results");
      List<Scored> scored = new ArrayList<>(results.size());
      for (JsonNode node : results) {
        scored.add(
            new Scored(node.path("index").asInt(), node.path("relevance_score").asDouble()));
      }
      return scored;
    } catch (Exception e) {
      LOG.warn("Rerank request failed: {}", e.getMessage());
      return List.of();
    }
  }

  private static String truncate(String value) {
    return value == null ? "" : value.substring(0, Math.min(200, value.length()));
  }
}
package com.arvindand.rag.model;

import java.time.Instant;
import java.util.List;

/**
 * Response model for chat interactions.
 *
 * @param content The AI-generated response content
 * @param conversationId The conversation ID for tracking
 * @param sources List of source documents used (if RAG was enabled)
 * @param timestamp Response generation timestamp
 * @param decision How the request was handled: {@code rag}, {@code direct}, {@code refused} or
 *     {@code no_answer}
 * @author Arvind Menon
 */
public record ChatResponse(
    String content,
    String conversationId,
    List<String> sources,
    Instant timestamp,
    String decision) {

  /** Creates a response from the normal RAG pipeline, defaulting the timestamp to now. */
  public static ChatResponse withSources(
      String content, String conversationId, List<String> sources) {
    return new ChatResponse(content, conversationId, sources, Instant.now(), "rag");
  }

  /** Creates a response produced by the query gate instead of the model. */
  public static ChatResponse gated(
      String content, String conversationId, List<String> suggestions, String decision) {
    return new ChatResponse(content, conversationId, suggestions, Instant.now(), decision);
  }
}
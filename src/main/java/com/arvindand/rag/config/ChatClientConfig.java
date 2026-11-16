package com.arvindand.rag.config;

import com.arvindand.rag.service.HybridDocumentRetriever;
import com.arvindand.rag.service.HybridRetriever;
import com.arvindand.rag.tools.DocumentTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for ChatClient with advanced RAG patterns.
 *
 * <p>This configuration showcases Spring AI 2.0's modular RAG architecture with:
 *
 * <ul>
 *   <li>Query rewriting for improved retrieval
 *   <li>Conversation memory with JDBC persistence
 *   <li>Vector store retrieval with similarity thresholds
 *   <li>Logging advisor for observability
 * </ul>
 *
 * @author Arvind Menon
 */
@Configuration
public class ChatClientConfig {

  private static final String DEFAULT_SYSTEM_PROMPT =
      """
      你是贵州财经大学学生手册问答助手。严格遵守以下规则：
      1. 只依据给出的【参考资料】回答，不引用外部知识，不猜测，不编造条文、金额、比例或时限。
      2. 先给结论，再给依据，并注明出自哪一章；资料里有条号就写条号。
      3. 如果参考资料不足以回答，直接说明"《贵州财经大学学生手册》中没有查到与此相关的明确规定"，
         再列出参考资料中最接近的 1-3 个章节供参考，并建议咨询辅导员或学生工作部（处）。
      4. 用简洁的中文回答；金额、比例、期限、次数必须与原文完全一致。
      """;

  // Retrieval knobs measured against the 20-question handbook evaluation set.
  @Value("${rag.retrieval.mode:hybrid_rerank}")
  private String retrievalMode;

  @Value("${rag.retrieval.top-k:6}")
  private int retrievalTopK;

  @Value("${rag.retrieval.candidates:40}")
  private int retrievalCandidates;

  @Value("${rag.retrieval.threshold:0.3}")
  private double retrievalThreshold;

  /**
   * Query rewriting costs one extra chat-model round trip. Hybrid recall already reaches hit@1 1.00
   * on the evaluation set without it, so it is off by default and can be re-enabled per deployment.
   */
  @Value("${rag.retrieval.rewrite:false}")
  private boolean rewriteQuery;

  /**
   * Creates a ChatClient with modular RAG pipeline and conversation memory.
   *
   * <p>The RAG pipeline uses:
   *
   * <ul>
   *   <li>{@link RewriteQueryTransformer} - Rewrites user queries for better retrieval
   *   <li>{@link HybridDocumentRetriever} - Dense + trigram recall fused with RRF
   *   <li>{@link MessageChatMemoryAdvisor} - Maintains conversation history
   * </ul>
   */
  @Bean
  ChatClient chatClient(
      ChatClient.Builder chatClientBuilder,
      VectorStore vectorStore,
      ChatMemory chatMemory,
      DocumentTools documentTools,
      HybridRetriever hybridRetriever) {

    return ragClientBuilder(chatClientBuilder, vectorStore, documentTools, hybridRetriever)
        .defaultSystem(DEFAULT_SYSTEM_PROMPT)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .build();
  }

  /** RAG client for /v1, where the caller supplies the complete conversation on every request. */
  @Bean
  ChatClient statelessRagChatClient(
      ChatClient.Builder chatClientBuilder,
      VectorStore vectorStore,
      DocumentTools documentTools,
      HybridRetriever hybridRetriever) {
    return ragClientBuilder(chatClientBuilder, vectorStore, documentTools, hybridRetriever).build();
  }

  private ChatClient.Builder ragClientBuilder(
      ChatClient.Builder chatClientBuilder,
      VectorStore vectorStore,
      DocumentTools documentTools,
      HybridRetriever hybridRetriever) {

    // Build the modular RAG advisor with optional query transformation
    var advisorBuilder =
        RetrievalAugmentationAdvisor.builder()
            // Hybrid recall (dense + trigram RRF) instead of dense-only. Measured on the
            // 20-question handbook set: dense-only hit@1 0.95, hybrid 1.00.
            .documentRetriever(
                new HybridDocumentRetriever(
                    hybridRetriever,
                    retrievalMode,
                    retrievalTopK,
                    retrievalCandidates,
                    retrievalThreshold));
    if (rewriteQuery) {
      advisorBuilder.queryTransformers(
          RewriteQueryTransformer.builder().chatClientBuilder(chatClientBuilder.clone()).build());
    }
    var ragAdvisor = advisorBuilder.build();

    return chatClientBuilder
        .clone()
        .defaultAdvisors(
            // RAG for document-grounded responses
            ragAdvisor,
            // Logging for observability
            new SimpleLoggerAdvisor())
        // @Tool methods the model can invoke to search the knowledge base on demand
        .defaultTools(documentTools);
  }

  /** Creates a basic ChatClient without RAG for simple chat operations. */
  @Bean
  ChatClient simpleChatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
    return chatClientBuilder
        .clone()
        .defaultSystem("You are a helpful AI assistant.")
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor())
        .build();
  }

  /**
   * Plain /v1 client with no memory advisor or default messages added to the supplied transcript.
   */
  @Bean
  ChatClient statelessSimpleChatClient(ChatClient.Builder chatClientBuilder) {
    return chatClientBuilder.clone().defaultAdvisors(new SimpleLoggerAdvisor()).build();
  }
}

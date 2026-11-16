package com.arvindand.rag.service;

import com.arvindand.rag.model.DocumentUploadResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service for document ingestion and management.
 *
 * <p>Supports PDF, Markdown, and plain-text documents. A single {@link #ingest} pipeline parses the
 * source into {@link Document}s, tags them with metadata, splits them into chunks, and stores the
 * chunks in the vector store for retrieval-augmented generation.
 *
 * @author Arvind Menon
 */
@Service
public class DocumentService {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);

  static final String META_DOCUMENT_ID = "document_id";
  static final String META_FILENAME = "filename";
  static final String META_SOURCE = "source";
  static final String META_CHAPTER = "chapter";
  static final String META_ARTICLE = "article";

  private final VectorStore vectorStore;

  // 500-token chunks: the default 800 is too coarse for regulation text, while the
  // original 200 split single articles apart and lost the surrounding context.
  private final TokenTextSplitter textSplitter =
      TokenTextSplitter.builder().withChunkSize(500).withMinChunkSizeChars(200).build();

  public DocumentService(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  /**
   * Uploads and processes an uploaded multipart file.
   *
   * @param file the multipart file to ingest
   * @return a response describing the outcome (never throws on parse/storage failure)
   */
  public DocumentUploadResponse uploadDocument(MultipartFile file) {
    String filename = file.getOriginalFilename();
    String documentId = UUID.randomUUID().toString();
    try {
      int chunks = ingest(file.getResource(), filename, documentId);
      LOG.info("Ingested document '{}' as {} chunks", filename, chunks);
      return DocumentUploadResponse.success(documentId, filename, chunks);
    } catch (Exception e) {
      LOG.error("Failed to ingest document '{}'", filename, e);
      return DocumentUploadResponse.failure(filename, e.getMessage());
    }
  }

  /**
   * Deletes every chunk belonging to a document.
   *
   * @param documentId the document id assigned at ingestion time
   */
  public void deleteDocument(String documentId) {
    vectorStore.delete("%s == '%s'".formatted(META_DOCUMENT_ID, documentId));
    LOG.info("Deleted document '{}'", documentId);
  }

  /**
   * The single ingestion pipeline shared by every entry point: read, tag, split, store.
   *
   * <p>Markdown and plain text are split on article boundaries (第X条) and every chunk is prefixed
   * with its chapter title, so a retrieved fragment still says which regulation it belongs to. PDFs
   * have no reliable article structure, so they keep the token splitter.
   *
   * @return the number of chunks written to the vector store
   */
  int ingest(Resource resource, String filename, String documentId) {
    List<Document> documents = read(resource, filename);
    boolean structured = !"pdf".equals(extensionOf(filename));
    String title =
        documents.isEmpty()
            ? filename
            : StructuralTextSplitter.titleOf(documents.get(0).getText(), filename);

    List<Document> chunks =
        structured ? structuralChunks(documents, filename, documentId, title) : tokenChunks(documents, filename, documentId);

    // Some OpenAI-compatible embedding providers (e.g. Alibaba Cloud Bailian /
    // DashScope) reject requests carrying more than 10 inputs, so embed in
    // small batches instead of sending every chunk at once.
    int batchSize = 10;
    for (int start = 0; start < chunks.size(); start += batchSize) {
      int end = Math.min(start + batchSize, chunks.size());
      vectorStore.add(chunks.subList(start, end));
    }
    return chunks.size();
  }

  private List<Document> structuralChunks(
      List<Document> documents, String filename, String documentId, String title) {
    List<Document> chunks = new ArrayList<>();
    for (Document doc : documents) {
      for (String piece : StructuralTextSplitter.split(doc.getText())) {
        Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
        metadata.put(META_DOCUMENT_ID, documentId);
        metadata.put(META_FILENAME, filename);
        metadata.put(META_SOURCE, filename);
        metadata.put(META_CHAPTER, title);
        metadata.put(META_ARTICLE, StructuralTextSplitter.articleOf(piece));
        chunks.add(new Document("【" + title + "】\n" + piece, metadata));
      }
    }
    return chunks;
  }

  private List<Document> tokenChunks(
      List<Document> documents, String filename, String documentId) {
    documents.forEach(
        doc -> {
          doc.getMetadata().put(META_DOCUMENT_ID, documentId);
          doc.getMetadata().put(META_FILENAME, filename);
          doc.getMetadata().put(META_SOURCE, filename);
        });
    return textSplitter.apply(documents);
  }

  /** Selects a reader based on the file extension; anything unknown is treated as plain text. */
  private List<Document> read(Resource resource, String filename) {
    try {
      return switch (extensionOf(filename)) {
        case "pdf" -> new PagePdfDocumentReader(resource).get();
        // Split Markdown ourselves so article boundaries survive; the Markdown reader
        // would hand back headed blocks we still had to token-split anyway.
        default -> List.of(new Document(resource.getContentAsString(StandardCharsets.UTF_8)));
      };
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Could not read '" + filename + "': " + e.getMessage(), e);
    }
  }

  private static String extensionOf(String filename) {
    if (filename == null) {
      return "";
    }
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
  }
}

package com.arvindand.rag.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits Chinese regulation text on structural boundaries instead of raw token counts.
 *
 * <p>The handbook is organised as 章 → 条, and a single 条 (article) is the smallest unit that
 * still means something on its own. Fixed-size token splitting cuts straight through articles, so a
 * retrieved chunk can start mid-sentence and lose the clause number the answer depends on.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>cut before every {@code 第X条}
 *   <li>merge consecutive articles up to {@value #MAX_CHARS} characters
 *   <li>split an oversized article at sentence boundaries ({@code 。；！？})
 *   <li>merge a trailing fragment shorter than {@value #MIN_CHARS} into its predecessor
 * </ul>
 *
 * @author Arvind Menon
 */
public final class StructuralTextSplitter {

  /** Upper bound for one chunk; a 条 longer than this is split by sentence. */
  static final int MAX_CHARS = 700;

  /** Fragments shorter than this carry too little context to retrieve well on their own. */
  static final int MIN_CHARS = 200;

  /** Start of an article: 第一条 / 第十二条 / 第 3 条, anchored at line start. */
  private static final Pattern ARTICLE_START =
      Pattern.compile("(?m)(?=^\\s*第[一二三四五六七八九十百零〇两0-9]{1,6}条)");

  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。；！？])");

  private StructuralTextSplitter() {}

  /**
   * Splits one document body into structure-aware chunks.
   *
   * @param text full document text, optionally with YAML front matter
   * @return non-empty chunks in document order
   */
  public static List<String> split(String text) {
    String body = stripFrontMatter(text).strip();
    if (body.isEmpty()) {
      return List.of();
    }

    List<String> articles = new ArrayList<>();
    for (String part : ARTICLE_START.split(body)) {
      String trimmed = part.strip();
      if (!trimmed.isEmpty()) {
        articles.add(trimmed);
      }
    }
    if (articles.isEmpty()) {
      articles.add(body);
    }

    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String article : articles) {
      if (article.length() > MAX_CHARS) {
        flush(chunks, current);
        chunks.addAll(splitLong(article));
      } else if (current.length() + article.length() + 1 > MAX_CHARS) {
        flush(chunks, current);
        current.append(article);
      } else {
        if (current.length() > 0) {
          current.append('\n');
        }
        current.append(article);
      }
    }
    flush(chunks, current);

    return mergeShortTails(chunks);
  }

  /** Extracts the chapter title, preferring an H1 heading over YAML front matter. */
  public static String titleOf(String text, String fallback) {
    String frontMatterTitle = null;
    for (String line : text.split("\\R")) {
      String trimmed = line.strip();
      if (trimmed.startsWith("# ")) {
        return stripQuotes(trimmed.substring(2).strip());
      }
      if (frontMatterTitle == null) {
        String value = trimmed.replaceFirst("^(?i)title\\s*:", "").strip();
        if (!value.equals(trimmed) && !value.isEmpty()) {
          frontMatterTitle = stripQuotes(value);
        }
      }
    }
    return frontMatterTitle != null ? frontMatterTitle : fallback;
  }

  private static String stripQuotes(String value) {
    String stripped = value.strip();
    while (stripped.length() >= 2
        && ((stripped.startsWith("\"") && stripped.endsWith("\""))
            || (stripped.startsWith("'") && stripped.endsWith("'"))
            || (stripped.startsWith("“") && stripped.endsWith("”")))) {
      stripped = stripped.substring(1, stripped.length() - 1).strip();
    }
    return stripped;
  }

  /** Returns the first article number (第X条) appearing in a chunk, or an empty string. */
  public static String articleOf(String chunk) {
    var matcher = Pattern.compile("第[一二三四五六七八九十百零〇两0-9]{1,6}条").matcher(chunk);
    return matcher.find() ? matcher.group() : "";
  }

  private static List<String> splitLong(String article) {
    List<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String sentence : SENTENCE_END.split(article)) {
      if (sentence.isEmpty()) {
        continue;
      }
      if (current.length() + sentence.length() > MAX_CHARS && current.length() > 0) {
        pieces.add(current.toString().strip());
        current.setLength(0);
      }
      current.append(sentence);
    }
    if (current.length() > 0) {
      pieces.add(current.toString().strip());
    }
    return pieces;
  }

  private static void flush(List<String> chunks, StringBuilder current) {
    String value = current.toString().strip();
    if (!value.isEmpty()) {
      chunks.add(value);
    }
    current.setLength(0);
  }

  private static List<String> mergeShortTails(List<String> chunks) {
    if (chunks.size() < 2) {
      return chunks;
    }
    List<String> merged = new ArrayList<>(chunks.size());
    for (String chunk : chunks) {
      if (!merged.isEmpty() && chunk.length() < MIN_CHARS) {
        int last = merged.size() - 1;
        merged.set(last, merged.get(last) + "\n" + chunk);
      } else {
        merged.add(chunk);
      }
    }
    return merged;
  }

  private static String stripFrontMatter(String text) {
    String stripped = text.strip();
    if (!stripped.startsWith("---")) {
      return stripped;
    }
    int end = stripped.indexOf("\n---", 3);
    if (end < 0) {
      return stripped;
    }
    int after = stripped.indexOf('\n', end + 4);
    return after < 0 ? "" : stripped.substring(after + 1);
  }
}
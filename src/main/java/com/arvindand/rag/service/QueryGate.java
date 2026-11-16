package com.arvindand.rag.service;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decides how a user message should be handled before any chat-model call.
 *
 * <p>Three outcomes short-circuit the RAG pipeline, which keeps the assistant inside its role and
 * avoids paying for a model call that can only produce an unsupported answer:
 *
 * <ul>
 *   <li>{@code REFUSE} — out-of-scope operations (changing grades, credentials, deleting data)
 *   <li>{@code DIRECT} — greetings and questions about the assistant itself
 *   <li>{@code NO_ANSWER} — retrieval found nothing above the confidence floor, so the honest reply
 *       is "the handbook does not say", plus the closest chapters to look at
 * </ul>
 *
 * <p>Everything else falls through to normal RAG.
 *
 * @author Arvind Menon
 */
@Service
public class QueryGate {

  /** How the message will be answered. */
  public enum Kind {
    RAG,
    DIRECT,
    REFUSE,
    NO_ANSWER
  }

  /**
   * The gate's verdict.
   *
   * @param kind how to answer
   * @param reply pre-written answer for non-RAG kinds; empty for {@code RAG}
   * @param suggestions related chapter titles shown with a {@code NO_ANSWER} reply
   * @param bestCosine highest dense similarity among retrieved chunks, or {@code null}
   */
  public record Verdict(
      Kind kind, String reply, List<String> suggestions, Double bestCosine, String reason) {}

  /**
   * Operations this assistant must not perform, no matter how the request is phrased. These are
   * academic-integrity and administrative boundaries, not content filtering of handbook topics.
   */
  private static final Pattern REFUSE =
      Pattern.compile(
          "(改|修改|调|篡改).{0,6}(成绩|分数|绩点)"
              + "|(成绩|分数).{0,4}(改成|改为|改到)"
              + "|(删除|清空|drop|truncate).{0,8}(数据|知识库|文档|记录|表)"
              + "|(数据库|后台|管理员|root).{0,6}(密码|口令|权限|密码是)"
              + "|(密码|口令|密钥|api[ _-]?key|token).{0,4}(是|多少|给我|告诉)"
              + "|(查|看|获取).{0,6}(别人|他人|同学).{0,6}(成绩|信息|档案|身份证)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern GREETING =
      Pattern.compile(
          "^(你好|您好|hi|hello|hey|在吗|在么|嗨|哈喽)[\\s!！。.,，~～]*$",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern SELF_QUERY =
      Pattern.compile("(你是谁|你叫什么|你能做什么|你会什么|你有什么功能|怎么用|如何使用|帮助|help)");

  private static final Pattern THANKS =
      Pattern.compile("^(谢谢|多谢|感谢|thanks|thank you|再见|拜拜|bye)[\\s!！。.,，~～]*$",
          Pattern.CASE_INSENSITIVE);

  /**
   * Questions asking for contact details. A dense score stays high for these because the topic
   * (dormitory, student affairs) matches even when the handbook never lists a phone number, so the
   * retrieved context is checked for the requested attribute instead of trusting the score.
   */
  private static final Pattern CONTACT_REQUEST =
      Pattern.compile("(联系电话|联系方式|电话号码|电话|号码|邮箱|网址)", Pattern.CASE_INSENSITIVE);

  private static final String GREETING_REPLY =
      "你好，我是贵州财经大学学生手册问答助手。我只依据《贵州财经大学学生手册》回答制度类问题，"
          + "比如奖学金标准、重修与学籍、宿舍管理、第二课堂学分、奖惩规定等。你想了解哪一块？";

  private static final String SELF_REPLY =
      "我是基于贵州财经大学学生手册的问答助手。回答只依据手册原文，并给出对应章节；"
          + "手册没写的内容我会直接告诉你没有，不会编造。你可以问：国家奖学金标准、重修次数、"
          + "宿舍管理规定、第二课堂学分要求、申诉流程等。";

  private static final String THANKS_REPLY = "不客气。还有别的手册问题可以继续问我。";

  private static final String REFUSE_REPLY =
      "这个问题超出我的职责范围。我只能依据学生手册回答制度类问题；"
          + "成绩修改、账号口令、数据删除、查询他人信息等操作，请通过辅导员或相关职能部门办理。";

  private final HybridRetriever hybridRetriever;
  private final String mode;
  private final int candidates;
  private final double minCosine;

  public QueryGate(
      HybridRetriever hybridRetriever,
      // The gate only checks whether anything relevant exists, so it stays on cheap hybrid recall
      // even when answer generation uses the cross-encoder reranker.
      @Value("${rag.gate.mode:hybrid}") String mode,
      @Value("${rag.gate.candidates:20}") int candidates,
      @Value("${rag.gate.min-cosine:0.45}") double minCosine) {
    this.hybridRetriever = hybridRetriever;
    this.mode = mode;
    this.candidates = candidates;
    this.minCosine = minCosine;
  }

  /**
   * Classifies a message.
   *
   * @param message the raw user message
   * @return the verdict; {@link Kind#RAG} means "carry on with the normal pipeline"
   */
  public Verdict check(String message) {
    String text = message == null ? "" : message.strip();
    if (text.isEmpty()) {
      return new Verdict(Kind.DIRECT, GREETING_REPLY, List.of(), null, "empty");
    }
    if (REFUSE.matcher(text).find()) {
      return new Verdict(Kind.REFUSE, REFUSE_REPLY, List.of(), null, "out_of_scope");
    }
    if (GREETING.matcher(text).matches()) {
      return new Verdict(Kind.DIRECT, GREETING_REPLY, List.of(), null, "greeting");
    }
    if (SELF_QUERY.matcher(text).find()) {
      return new Verdict(Kind.DIRECT, SELF_REPLY, List.of(), null, "self_query");
    }
    if (THANKS.matcher(text).matches()) {
      return new Verdict(Kind.DIRECT, THANKS_REPLY, List.of(), null, "thanks");
    }

    // Pre-flight retrieval: an unsupported question is answered without spending a model call.
    List<HybridRetriever.Hit> hits = hybridRetriever.retrieve(text, 3, candidates, mode, 0.0);
    Double bestCosine = null;
    for (HybridRetriever.Hit hit : hits) {
      if (hit.vectorScore() != null && (bestCosine == null || hit.vectorScore() > bestCosine)) {
        bestCosine = hit.vectorScore();
      }
    }

    if (hits.isEmpty() || (bestCosine != null && bestCosine < minCosine)) {
      return new Verdict(
          Kind.NO_ANSWER, noAnswerReply(hits), chapterTitles(hits), bestCosine, "no_relevant_chunk");
    }

    // A contact request only passes when the retrieved rules actually contain contact details.
    if (CONTACT_REQUEST.matcher(text).find() && !retrievedMentions(hits, CONTACT_REQUEST)) {
      return new Verdict(
          Kind.NO_ANSWER,
          noAnswerReply(hits),
          chapterTitles(hits),
          bestCosine,
          "contact_not_in_corpus");
    }

    return new Verdict(Kind.RAG, "", List.of(), bestCosine, "ok");
  }

  private static boolean retrievedMentions(List<HybridRetriever.Hit> hits, Pattern pattern) {
    for (HybridRetriever.Hit hit : hits) {
      if (hit.content() != null && pattern.matcher(hit.content()).find()) {
        return true;
      }
    }
    return false;
  }

  private String noAnswerReply(List<HybridRetriever.Hit> hits) {
    String base = "《贵州财经大学学生手册》里没有查到与此相关的明确规定。";
    List<String> titles = chapterTitles(hits);
    if (titles.isEmpty()) {
      return base + "建议换一种问法，或咨询辅导员、学生工作部（处）。";
    }
    StringBuilder sb = new StringBuilder(base);
    sb.append("\n\n手册里可能相关的内容：");
    for (int i = 0; i < titles.size(); i++) {
      sb.append("\n").append(i + 1).append(". 《").append(titles.get(i)).append("》");
    }
    sb.append("\n\n如果这些都不是你要找的，建议咨询辅导员或学生工作部（处）。");
    return sb.toString();
  }

  private static List<String> chapterTitles(List<HybridRetriever.Hit> hits) {
    return hits.stream()
        .map(HybridRetriever.Hit::chapter)
        .filter(chapter -> chapter != null && !chapter.isBlank())
        .distinct()
        .limit(3)
        .toList();
  }
}
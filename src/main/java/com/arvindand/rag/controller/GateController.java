package com.arvindand.rag.controller;

import com.arvindand.rag.service.QueryGate;
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
 * Exposes the routing decision without generating an answer.
 *
 * <p>The evaluation harness uses this to check whether the assistant correctly declines questions
 * the handbook does not cover, and to see the confidence that drove the decision. It costs one
 * embedding call and no chat-model call.
 *
 * @author Arvind Menon
 */
@RestController
@RequestMapping("/api/v2/gate")
public class GateController {

  private final QueryGate gate;

  public GateController(QueryGate gate) {
    this.gate = gate;
  }

  /** Classifies a single message. */
  @GetMapping
  public Map<String, Object> check(@RequestParam("q") String query) {
    return toRow(query, gate.check(query));
  }

  /** Classifies a batch of questions in one request; used by the evaluation harness. */
  @PostMapping("/batch")
  public List<Map<String, Object>> batch(@RequestBody List<String> queries) {
    List<Map<String, Object>> rows = new ArrayList<>(queries.size());
    for (String query : queries) {
      rows.add(toRow(query, gate.check(query)));
    }
    return rows;
  }

  private static Map<String, Object> toRow(String query, QueryGate.Verdict verdict) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("question", query);
    row.put("decision", verdict.kind().name().toLowerCase(java.util.Locale.ROOT));
    row.put("bestCosine", verdict.bestCosine());
    row.put("reason", verdict.reason());
    row.put("suggestions", verdict.suggestions());
    row.put("reply", verdict.reply());
    return row;
  }
}
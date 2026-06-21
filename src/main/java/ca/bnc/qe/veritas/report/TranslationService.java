package ca.bnc.qe.veritas.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Translates short report strings EN→FR for the bilingual report. This is the <b>only</b> part of report
 * generation that needs the LLM, and it deliberately uses the <b>cheapest</b> tier ({@link ModelTier#ECONOMY})
 * — translation needs no reasoning. Calls are <b>batched</b> (one request for all strings) and <b>cached</b>
 * in-memory by source text, so repeated/identical strings cost nothing. Failures are non-fatal: the English
 * text is returned as the fallback, so the report still renders.
 */
@Service
@Slf4j
public class TranslationService {

    private final LlmGateway llm;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final JsonBlockExtractor jsonExtractor;
    private final ObjectMapper objectMapper;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public TranslationService(LlmGateway llm, ModelSelector modelSelector, CostRecorder costRecorder,
                              JsonBlockExtractor jsonExtractor, ObjectMapper objectMapper) {
        this.llm = llm;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.jsonExtractor = jsonExtractor;
        this.objectMapper = objectMapper;
    }

    /** Returns an EN→FR map for the distinct non-blank texts. English is used as the fallback on any failure. */
    public Map<String, String> toFrench(Collection<String> texts, String owner) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> todo = new ArrayList<>();
        for (String t : texts) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String cached = cache.get(t);
            if (cached != null) {
                out.put(t, cached);
            } else if (!todo.contains(t)) {
                todo.add(t);
            }
        }
        if (todo.isEmpty()) {
            return out;
        }
        if (!llm.isAvailable()) {
            todo.forEach(t -> out.put(t, t));   // deterministic-only mode → English fallback, no cost
            return out;
        }
        try {
            StringBuilder p = new StringBuilder("[TRANSLATE]\nTranslate each English report string to Canadian "
                    + "French (Québec). Preserve technical terms, code, paths, and {placeholders} verbatim. Return "
                    + "ONLY one fenced ```json block: an object keyed by the integer index with the French string. "
                    + "No prose.\n\nStrings:\n");
            for (int i = 0; i < todo.size(); i++) {
                p.append(i).append(": ").append(todo.get(i).replace("\n", " ").replace("\r", " ")).append("\n");
            }
            String model = modelSelector.resolveTier(ModelTier.ECONOMY);   // cheapest tier — no reasoning needed
            String raw = llm.complete(p.toString(), model);
            costRecorder.record("report-translation", "translate", model, p.toString(), raw, owner);
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            for (int i = 0; i < todo.size(); i++) {
                String en = todo.get(i);
                String fr = node.path(String.valueOf(i)).asText("");
                String val = fr.isBlank() ? en : fr;
                cache.put(en, val);
                out.put(en, val);
            }
        } catch (Exception e) {
            log.warn("Translation failed ({}); rendering English in both languages.", e.getMessage());
            todo.forEach(t -> out.putIfAbsent(t, t));   // non-fatal fallback (not cached, so a later run retries)
        }
        return out;
    }
}

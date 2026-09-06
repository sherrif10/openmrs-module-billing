package org.openmrs.module.billing.api.evaluator;

import lombok.extern.slf4j.Slf4j;
import org.openmrs.module.billing.api.model.BillExemption;
import org.openmrs.module.billing.api.model.BillExemptionRule;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ExemptionRuleEngine {
	
	private final Map<ScriptType, ExemptionEvaluator> evaluatorsByType = new EnumMap<>(ScriptType.class);
	
	public ExemptionRuleEngine(List<ExemptionEvaluator> evaluators) {
		for (ExemptionEvaluator evaluator : evaluators) {
			evaluatorsByType.put(evaluator.getSupportedType(), evaluator);
		}
	}
	
	/**
	 * Evaluates a single rule. When no evaluator is registered for the rule's script type the rule is
	 * treated as not applicable rather than throwing. Callers such as GenerateBillFromOrderAdvice run
	 * this inside a broad catch-all, so throwing here would abort bill creation entirely and silently
	 * produce no line item at all. Returning false keeps billing intact; the warning is the only signal
	 * that the exemption was not honoured, so it is logged at WARN.
	 */
	public boolean evaluateRule(BillExemptionRule rule, Map<String, Object> variables) {
		ExemptionEvaluator evaluator = evaluatorsByType.get(rule.getScriptType());
		if (evaluator == null) {
			log.warn(
			    "No evaluator registered for script type {}; treating exemption rule as not applicable. "
			            + "The patient will be billed normally even though an exemption is configured.",
			    rule.getScriptType());
			return false;
		}
		return evaluator.evaluate(rule.getScript(), variables);
	}
	
	public boolean isExemptionApplicable(BillExemption exemption, Map<String, Object> variables) {
		if (exemption.getRules() == null || exemption.getRules().isEmpty()) {
			return false;
		}
		
		return exemption.getRules().stream().filter(r -> !r.getVoided()).anyMatch(r -> evaluateRule(r, variables));
	}
}

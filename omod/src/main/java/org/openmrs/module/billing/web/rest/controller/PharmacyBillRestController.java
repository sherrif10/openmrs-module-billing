package org.openmrs.module.billing.web.rest.controller;

import org.openmrs.Encounter;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.billing.advice.PharmacyBilling;
import org.openmrs.module.billing.api.model.Bill;
import org.openmrs.module.billing.web.rest.controller.base.CashierResourceController;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MediHealth (2026-11-10): the pharmacy bill of one prescription, for the Dispensing screen.
 * <ul>
 * <li>GET /ws/rest/v1/billing/api/pharmacy-bill?encounter=UUID : drugs, unit prices, line totals,
 * total</li>
 * <li>POST /ws/rest/v1/billing/api/pharmacy-bill {"encounter": UUID, "orders": [UUID...]} : one
 * PENDING bill</li>
 * </ul>
 */
@Controller
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + CashierResourceController.BILLING_NAMESPACE
        + "/api/pharmacy-bill")
public class PharmacyBillRestController extends BaseRestController {
	
	/**
	 * The dispensing app's "create dispense" privilege: the pharmacy users who see the Dispense button.
	 */
	static final String DISPENSE_PRIVILEGE = "Task: dispensing.create.dispense";
	
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> preview(@RequestParam("encounter") String encounterUuid) {
		requireAuthenticated();
		Encounter encounter = Context.getEncounterService().getEncounterByUuid(encounterUuid);
		if (encounter == null) {
			return error(HttpStatus.NOT_FOUND, "No encounter " + encounterUuid);
		}
		List<Map<String, Object>> lines = new PharmacyBilling().preview(encounter);
		BigDecimal total = BigDecimal.ZERO;
		for (Map<String, Object> line : lines) {
			if (line.get("total") != null && line.get("billed") == null) {
				total = total.add((BigDecimal) line.get("total"));
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("encounter", encounterUuid);
		out.put("lines", lines);
		out.put("unbilledTotal", total);
		return new ResponseEntity<Object>(out, HttpStatus.OK);
	}
	
	@RequestMapping(method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> generate(@RequestBody Map<String, Object> body) {
		requireAuthenticated();
		if (!Context.getAuthenticatedUser().isSuperUser() && !Context.hasPrivilege(DISPENSE_PRIVILEGE)) {
			return error(HttpStatus.FORBIDDEN, "Only pharmacy (privilege " + DISPENSE_PRIVILEGE + ") can generate the bill");
		}
		Encounter encounter = Context.getEncounterService().getEncounterByUuid(String.valueOf(body.get("encounter")));
		if (encounter == null) {
			return error(HttpStatus.NOT_FOUND, "No encounter " + body.get("encounter"));
		}
		Object orders = body.get("orders");
		if (!(orders instanceof Collection) || ((Collection<?>) orders).isEmpty()) {
			return error(HttpStatus.BAD_REQUEST, "Choose at least one drug to bill");
		}
		List<String> uuids = new ArrayList<>();
		for (Object o : (Collection<?>) orders) {
			uuids.add(String.valueOf(o));
		}
		try {
			Bill bill = new PharmacyBilling().generate(encounter, uuids);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("uuid", bill.getUuid());
			out.put("receiptNumber", bill.getReceiptNumber());
			out.put("status", String.valueOf(bill.getStatus()));
			out.put("cashPoint", bill.getCashPoint() != null ? bill.getCashPoint().getName() : null);
			out.put("total", bill.getTotal());
			out.put("lines", bill.getLineItems().size());
			return new ResponseEntity<Object>(out, HttpStatus.CREATED);
		}
		catch (IllegalArgumentException | IllegalStateException e) {
			return error(HttpStatus.BAD_REQUEST, e.getMessage());
		}
	}
	
	private static void requireAuthenticated() {
		if (!Context.isAuthenticated()) {
			throw new APIAuthenticationException("Log in first");
		}
	}
	
	private static ResponseEntity<Object> error(HttpStatus status, String message) {
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("error", message);
		return new ResponseEntity<Object>(err, status);
	}
}

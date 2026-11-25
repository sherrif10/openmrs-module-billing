package org.openmrs.module.billing.web.rest.controller;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.billing.pay1.Pay1Client;
import org.openmrs.module.billing.pay1.Pay1Settings;
import org.openmrs.module.billing.web.rest.controller.base.CashierResourceController;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MediHealth (2026-11-25): Pay1One connection settings and test, for the page
 * /medihealth/moduleResources/billing/pay1.html. Admins only (privilege "Manage Global
 * Properties").
 * <ul>
 * <li>GET /ws/rest/v1/billing/api/pay1/settings : address, username, billerID, whether a password
 * is saved</li>
 * <li>POST /ws/rest/v1/billing/api/pay1/settings : save (a blank password keeps the saved one)</li>
 * <li>POST /ws/rest/v1/billing/api/pay1/test : test the saved settings, or the typed ones without
 * saving</li>
 * <li>POST /ws/rest/v1/billing/api/pay1/lookup {"reference"} : the status of one Pay1
 * transaction</li>
 * </ul>
 */
@Controller
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + CashierResourceController.BILLING_NAMESPACE + "/api/pay1")
public class Pay1RestController extends BaseRestController {
	
	static final String PRIVILEGE = "Manage Global Properties";
	
	@RequestMapping(value = "/settings", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> getSettings() {
		ResponseEntity<Object> denied = checkAccess();
		if (denied != null) {
			return denied;
		}
		return new ResponseEntity<Object>(describe(Pay1Settings.load()), HttpStatus.OK);
	}
	
	@RequestMapping(value = "/settings", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> saveSettings(@RequestBody Map<String, Object> body) {
		ResponseEntity<Object> denied = checkAccess();
		if (denied != null) {
			return denied;
		}
		Pay1Settings s = merged(body);
		if (StringUtils.isBlank(s.getBaseUrl()) || !s.getBaseUrl().toLowerCase().startsWith("https://")) {
			return error(HttpStatus.BAD_REQUEST, "The Pay1 address must start with https://");
		}
		Pay1Settings toSave = new Pay1Settings();
		toSave.setBaseUrl(s.getBaseUrl());
		toSave.setUsername(s.getUsername());
		toSave.setBillerId(s.getBillerId());
		toSave.setPassword(str(body.get("password")));
		toSave.save();
		return new ResponseEntity<Object>(describe(Pay1Settings.load()), HttpStatus.OK);
	}
	
	@RequestMapping(value = "/test", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> test(@RequestBody(required = false) Map<String, Object> body) {
		ResponseEntity<Object> denied = checkAccess();
		if (denied != null) {
			return denied;
		}
		Map<String, Object> typed = body == null ? Collections.<String, Object> emptyMap() : body;
		Pay1Settings saved = Pay1Settings.load();
		Pay1Settings s = merged(typed);
		boolean otherPlace = !StringUtils.equalsIgnoreCase(s.getBaseUrl(), saved.getBaseUrl())
		        || !StringUtils.equals(s.getUsername(), saved.getUsername());
		if (otherPlace && str(typed.get("password")) == null) {
			// never send the saved password to an address or account it was not saved for
			return error(HttpStatus.BAD_REQUEST, "You changed the address or username: type the password too");
		}
		Pay1Client client = new Pay1Client(s);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("address", s.getBaseUrl());
		out.put("merchant", s.merchantCode());
		out.put("billerId", s.getBillerId());
		
		Pay1Client.Result depts = client.get("/departments/fetch");
		out.put("connected", depts.ok());
		out.put("check", Pay1Client.summary(depts));
		if (depts.ok()) {
			out.put("departments", pick(depts, "departmentName"));
			Pay1Client.Result services = client.get("/services/fetch");
			out.put("services", services.ok() ? pick(services, "serviceName") : null);
			out.put("servicesCheck", Pay1Client.summary(services));
		}
		return new ResponseEntity<Object>(out, HttpStatus.OK);
	}
	
	@RequestMapping(value = "/lookup", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> lookup(@RequestBody Map<String, Object> body) {
		ResponseEntity<Object> denied = checkAccess();
		if (denied != null) {
			return denied;
		}
		String reference = str(body.get("reference"));
		if (reference == null) {
			return error(HttpStatus.BAD_REQUEST, "Type a Pay1 reference or an invoice ID");
		}
		Map<String, Object> req = new LinkedHashMap<>();
		req.put("paymentType", "INVOICE");
		req.put("reference", reference);
		Pay1Client.Result r = new Pay1Client(Pay1Settings.load()).post("/emr/initialize/txn/validate", req);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("reference", reference);
		out.put("check", Pay1Client.summary(r));
		out.put("transaction", r.ok() && r.body != null ? r.body.get("data") : null);
		return new ResponseEntity<Object>(out, HttpStatus.OK);
	}
	
	/**
	 * The saved settings with whatever the page typed on top; a blank password means "use the saved
	 * one".
	 */
	private static Pay1Settings merged(Map<String, Object> body) {
		Pay1Settings s = Pay1Settings.load();
		if (body.containsKey("baseUrl")) {
			s.setBaseUrl(str(body.get("baseUrl")));
		}
		if (body.containsKey("username")) {
			s.setUsername(str(body.get("username")));
		}
		if (body.containsKey("billerId")) {
			s.setBillerId(str(body.get("billerId")));
		}
		if (str(body.get("password")) != null) {
			s.setPassword(str(body.get("password")));
		}
		return s;
	}
	
	private static Map<String, Object> describe(Pay1Settings s) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("baseUrl", s.getBaseUrl());
		out.put("username", s.getUsername());
		out.put("billerId", s.getBillerId());
		out.put("passwordSaved", StringUtils.isNotBlank(s.getPassword()));
		out.put("merchant", s.merchantCode());
		out.put("problem", s.problem());
		return out;
	}
	
	@SuppressWarnings("unchecked")
	private static List<String> pick(Pay1Client.Result r, String field) {
		List<String> names = new ArrayList<>();
		Object data = r.body == null ? null : r.body.get("data");
		if (data instanceof List) {
			for (Object row : (List<Object>) data) {
				if (row instanceof Map && ((Map<String, Object>) row).get(field) != null) {
					names.add(String.valueOf(((Map<String, Object>) row).get(field)));
				}
			}
		}
		return names;
	}
	
	private static String str(Object o) {
		return o == null ? null : StringUtils.trimToNull(String.valueOf(o));
	}
	
	private static ResponseEntity<Object> checkAccess() {
		if (!Context.isAuthenticated()) {
			throw new APIAuthenticationException("Log in first");
		}
		if (!Context.getAuthenticatedUser().isSuperUser() && !Context.hasPrivilege(PRIVILEGE)) {
			return error(HttpStatus.FORBIDDEN, "Only administrators can see or change the Pay1 connection");
		}
		return null;
	}
	
	private static ResponseEntity<Object> error(HttpStatus status, String message) {
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("error", message);
		return new ResponseEntity<Object>(err, status);
	}
}

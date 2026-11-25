package org.openmrs.module.billing.web.rest.controller;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.billing.api.BillService;
import org.openmrs.module.billing.api.BillableServiceService;
import org.openmrs.module.billing.api.CashPointService;
import org.openmrs.module.billing.api.base.PagingInfo;
import org.openmrs.module.billing.api.model.Bill;
import org.openmrs.module.billing.api.model.BillableService;
import org.openmrs.module.billing.api.model.CashPoint;
import org.openmrs.module.billing.api.search.BillSearch;
import org.openmrs.module.billing.api.search.BillableServiceSearch;
import org.openmrs.module.billing.api.search.CashPointSearch;
import org.openmrs.module.billing.pay1.Pay1BillBuilder;
import org.openmrs.module.billing.pay1.Pay1Client;
import org.openmrs.module.billing.pay1.Pay1Mapping;
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
import org.springframework.web.bind.annotation.RequestParam;
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
	
	// ---- Step 2: mapping (service type -> Pay1 department, PHC -> terminal) and bill preview ----
	
	@RequestMapping(value = "/mapping", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> getMapping() {
		ResponseEntity<Object> denied = checkAccess();
		if (denied != null) {
			return denied;
		}
		Pay1Mapping m = Pay1Mapping.load();
		Map<String, Object> out = new LinkedHashMap<>();
		
		// our service types, with how many priced services each has
		Map<String, Map<String, Object>> types = new LinkedHashMap<>();
		Map<String, Object> stock = new LinkedHashMap<>();
		stock.put("key", Pay1Mapping.STOCK_ITEM);
		stock.put("label", "Drugs billed from stock");
		stock.put("services", null);
		types.put(Pay1Mapping.STOCK_ITEM, stock);
		BillableServiceSearch search = new BillableServiceSearch();
		for (BillableService s : Context.getService(BillableServiceService.class).getBillableServices(search, null)) {
			String key = s.getServiceType() != null ? s.getServiceType().getUuid() : Pay1Mapping.NO_TYPE;
			Map<String, Object> t = types.get(key);
			if (t == null) {
				t = new LinkedHashMap<>();
				t.put("key", key);
				t.put("label", s.getServiceType() != null ? s.getServiceType().getDisplayString() : "Services with no type");
				t.put("services", 0);
				types.put(key, t);
			}
			t.put("services", (Integer) t.get("services") + 1);
		}
		List<Map<String, Object>> typeList = new ArrayList<>();
		for (Map<String, Object> t : types.values()) {
			t.put("pay1Department", m.getDepartments().get(t.get("key")));
			typeList.add(t);
		}
		out.put("types", typeList);
		
		// PHCs = the locations of the cash points
		Map<String, Map<String, Object>> phcs = new LinkedHashMap<>();
		for (CashPoint cp : Context.getService(CashPointService.class).getCashPoints(CashPointSearch.builder().build(),
		    null)) {
			if (cp.getLocation() == null) {
				continue;
			}
			Map<String, Object> p = phcs.get(cp.getLocation().getUuid());
			if (p == null) {
				p = new LinkedHashMap<>();
				p.put("uuid", cp.getLocation().getUuid());
				p.put("name", cp.getLocation().getName());
				p.put("cashPoints", new ArrayList<String>());
				p.put("terminalId", m.getTerminals().get(cp.getLocation().getUuid()));
				phcs.put(cp.getLocation().getUuid(), p);
			}
			@SuppressWarnings("unchecked")
			List<String> names = (List<String>) p.get("cashPoints");
			names.add(cp.getName());
		}
		out.put("phcs", new ArrayList<Object>(phcs.values()));
		out.put("defaultDepartment", m.getDefaultDepartment());
		out.put("channelName", m.getChannelName());
		
		// Pay1's own departments, to pick from (needs a working connection)
		Pay1Client.Result depts = new Pay1Client(Pay1Settings.load()).get("/departments/fetch");
		out.put("pay1Departments", depts.ok() ? pick(depts, "departmentName") : null);
		out.put("pay1Check", Pay1Client.summary(depts));
		return new ResponseEntity<Object>(out, HttpStatus.OK);
	}
	
	@RequestMapping(value = "/mapping", method = RequestMethod.POST)
	@ResponseBody
	@SuppressWarnings("unchecked")
	public ResponseEntity<Object> saveMapping(@RequestBody Map<String, Object> body) {
		ResponseEntity<Object> denied = checkAccess();
		if (denied != null) {
			return denied;
		}
		Pay1Mapping m = Pay1Mapping.load();
		if (body.get("departments") instanceof Map) {
			m.setDepartments(strings((Map<String, Object>) body.get("departments")));
		}
		if (body.get("terminals") instanceof Map) {
			m.setTerminals(strings((Map<String, Object>) body.get("terminals")));
		}
		if (body.containsKey("defaultDepartment")) {
			m.setDefaultDepartment(str(body.get("defaultDepartment")));
		}
		if (body.containsKey("channelName")) {
			m.setChannelName(str(body.get("channelName")));
		}
		m.save();
		return getMapping();
	}
	
	/** Recent bills to preview, newest first; ?q= a receipt number or part of a patient name. */
	@RequestMapping(value = "/bills", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> bills(@RequestParam(value = "q", required = false) String q) {
		ResponseEntity<Object> denied = checkAccess();
		if (denied != null) {
			return denied;
		}
		BillService service = Context.getService(BillService.class);
		List<Bill> found = new ArrayList<>();
		String query = StringUtils.trimToNull(q);
		if (query != null) {
			Bill byReceipt = service.getBillByReceiptNumber(query);
			if (byReceipt != null) {
				found.add(byReceipt);
			} else {
				BillSearch search = new BillSearch();
				search.setPatientName(query);
				found.addAll(service.getBills(search, new PagingInfo(1, 25)));
			}
		} else {
			found.addAll(service.getBills(new BillSearch(), new PagingInfo(1, 25)));
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Bill b : found) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("uuid", b.getUuid());
			row.put("receiptNumber", b.getReceiptNumber());
			row.put("patient",
			    b.getPatient() != null && b.getPatient().getPersonName() != null
			            ? b.getPatient().getPersonName().getFullName()
			            : null);
			row.put("cashPoint", b.getCashPoint() != null ? b.getCashPoint().getName() : null);
			row.put("status", String.valueOf(b.getStatus()));
			row.put("total", b.getTotal());
			row.put("dateCreated", b.getDateCreated());
			out.add(row);
		}
		return new ResponseEntity<Object>(out, HttpStatus.OK);
	}
	
	/** What would be sent to Pay1 for one bill, with problems and warnings. Nothing is sent. */
	@RequestMapping(value = "/preview", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> preview(@RequestParam("bill") String billUuid) {
		ResponseEntity<Object> denied = checkAccess();
		if (denied != null) {
			return denied;
		}
		Bill bill = Context.getService(BillService.class).getBillByUuid(billUuid);
		if (bill == null) {
			return error(HttpStatus.NOT_FOUND, "No bill " + billUuid);
		}
		Pay1BillBuilder.Built built = new Pay1BillBuilder(Pay1Settings.load(), Pay1Mapping.load()).build(bill);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bill", billUuid);
		out.put("receiptNumber", bill.getReceiptNumber());
		out.put("billTotal", bill.getTotal());
		out.put("ready", built.ready());
		out.put("problems", built.problems);
		out.put("warnings", built.warnings);
		out.put("pay1Request", built.body);
		return new ResponseEntity<Object>(out, HttpStatus.OK);
	}
	
	private static Map<String, String> strings(Map<String, Object> in) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : in.entrySet()) {
			String v = str(e.getValue());
			if (v != null) {
				out.put(e.getKey(), v);
			}
		}
		return out;
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

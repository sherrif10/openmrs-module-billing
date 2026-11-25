package org.openmrs.module.billing.pay1;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PersonAttribute;
import org.openmrs.api.context.Context;
import org.openmrs.module.billing.api.model.Bill;
import org.openmrs.module.billing.api.model.BillLineItem;
import org.openmrs.module.billing.api.model.BillStatus;
import org.openmrs.module.billing.api.model.BillableService;
import org.openmrs.module.stockmanagement.api.model.StockItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MediHealth (2026-11-25): turns a MediHealth bill into the body of Pay1's "Initialize Single Bill"
 * (POST /emr/initialize/txn/new), and lists what is missing. It only builds; nothing is sent here.
 * <p>
 * Pay1 rules found by testing (25 Nov): totalAmount must equal the sum of the lines ("Lines total
 * mismatch"), and an invoiceID can never be sent again with different details (409), so the body
 * must be complete before it is sent.
 */
public class Pay1BillBuilder {
	
	/** MediHealth ID, the patient number printed on cards. */
	static final String MEDIHEALTH_ID_TYPE = "05a29f94-c0ed-11e2-94be-8c13b969e334";
	
	static final String PHONE_ATTRIBUTE = "Telephone Number";
	
	/** Children usually have no phone of their own: use the next of kin's. */
	static final String NEXT_OF_KIN_PHONE_ATTRIBUTE = "Next of Kin Phone";
	
	public static final String INVOICE_PREFIX = "MH-";
	
	/** The result: the body Pay1 would get, blocking problems, and warnings that do not block. */
	public static class Built {
		
		public Map<String, Object> body = new LinkedHashMap<>();
		
		public List<String> problems = new ArrayList<>();
		
		public List<String> warnings = new ArrayList<>();
		
		public boolean ready() {
			return problems.isEmpty();
		}
	}
	
	private final Pay1Settings settings;
	
	private final Pay1Mapping mapping;
	
	public Pay1BillBuilder(Pay1Settings settings, Pay1Mapping mapping) {
		this.settings = settings;
		this.mapping = mapping;
	}
	
	public static String invoiceId(Bill bill) {
		return INVOICE_PREFIX + StringUtils.defaultIfBlank(bill.getReceiptNumber(), bill.getUuid());
	}
	
	/** The key of a line's service type in the department mapping. */
	public static String typeKey(BillLineItem line) {
		if (line.getItem() != null) {
			return Pay1Mapping.STOCK_ITEM;
		}
		BillableService s = line.getBillableService();
		return s != null && s.getServiceType() != null ? s.getServiceType().getUuid() : Pay1Mapping.NO_TYPE;
	}
	
	public Built build(Bill bill) {
		Built b = new Built();
		Patient patient = bill.getPatient();
		Location phc = bill.getCashPoint() != null ? bill.getCashPoint().getLocation() : null;
		String terminal = mapping.terminalFor(phc);
		String customerId = medihealthId(patient);
		
		if (StringUtils.isBlank(settings.getBillerId())) {
			b.problems.add("The Pay1 Biller ID is not set (Connection settings)");
		}
		if (bill.getCashPoint() == null) {
			b.problems.add("The bill has no cash point, so its PHC is unknown");
		} else if (terminal == null) {
			b.problems.add("No Pay1 terminal ID for " + (phc != null ? phc.getName() : bill.getCashPoint().getName()));
		}
		if (customerId == null) {
			b.warnings.add("The patient has no MediHealth ID; the patient's system ID is sent instead");
			customerId = patient != null ? patient.getUuid() : null;
		}
		if (bill.getStatus() == BillStatus.PAID) {
			b.problems.add("The bill is already PAID in MediHealth");
		} else if (bill.getTotalPayments() != null && bill.getTotalPayments().signum() > 0) {
			b.warnings.add("Part of this bill is already paid in MediHealth (" + plain(bill.getTotalPayments()) + ")");
		}
		
		List<Map<String, Object>> lines = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		if (bill.getLineItems() != null) {
			for (BillLineItem line : bill.getLineItems()) {
				if (line == null || Boolean.TRUE.equals(line.getVoided())) {
					continue;
				}
				String description = description(line);
				String department = mapping.departmentFor(typeKey(line));
				BigDecimal price = line.getPrice() == null ? BigDecimal.ZERO : line.getPrice();
				int qty = line.getQuantity() == null ? 0 : line.getQuantity();
				BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));
				if (department == null) {
					b.problems.add("No Pay1 department for \"" + description + "\" (" + typeLabel(line) + ")");
				}
				if (qty <= 0 || price.signum() <= 0) {
					b.problems.add("\"" + description + "\" has quantity " + qty + " and price " + plain(price)
					        + "; Pay1 needs both above zero");
				}
				Map<String, Object> l = new LinkedHashMap<>();
				l.put("LineId", line.getUuid());
				l.put("Amount", number(amount));
				l.put("Description", description);
				l.put("ItemCode", itemCode(line));
				l.put("Department", department);
				l.put("Quantity", String.valueOf(qty));
				l.put("UnitPrice", plain(price));
				l.put("Discount", "0");
				lines.add(l);
				total = total.add(amount);
			}
		}
		if (lines.isEmpty()) {
			b.problems.add("The bill has no lines");
		}
		
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("customerName",
		    patient != null && patient.getPersonName() != null ? patient.getPersonName().getFullName() : "");
		meta.put("customerEmail", "");
		meta.put("customerPhone", phone(patient));
		meta.put("customerAddress", "");
		meta.put("customerAlternateId", customerId + (terminal != null ? "|" + terminal : ""));
		if (StringUtils.isBlank((String) meta.get("customerPhone"))) {
			b.warnings.add("No telephone number for the patient or the next of kin");
		}
		
		b.body.put("billerID", settings.getBillerId());
		b.body.put("paymentType", "INVOICE");
		b.body.put("callbackUrl", "");
		b.body.put("customerID", customerId);
		b.body.put("narrative", "MediHealth bill " + StringUtils.defaultString(bill.getReceiptNumber())
		        + (bill.getCashPoint() != null ? " - " + bill.getCashPoint().getName() : ""));
		b.body.put("terminalID", terminal);
		b.body.put("invoiceID", invoiceId(bill));
		b.body.put("channelName", mapping.getChannelName());
		b.body.put("currency", "NGN");
		b.body.put("totalAmount", number(total));
		b.body.put("customerMeta", meta);
		b.body.put("lines", lines);
		return b;
	}
	
	static String description(BillLineItem line) {
		StockItem item = line.getItem();
		if (item != null) {
			if (item.getDrug() != null) {
				return item.getDrug().getName();
			}
			return StringUtils.defaultIfBlank(item.getCommonName(), "Stock item");
		}
		BillableService s = line.getBillableService();
		return s != null ? StringUtils.defaultIfBlank(s.getName(), s.getShortName()) : "Bill line";
	}
	
	/**
	 * Our own stable code: the drug (stock item lines) or the billable service. Pay1 links it via its
	 * emrCode.
	 */
	static String itemCode(BillLineItem line) {
		StockItem item = line.getItem();
		if (item != null) {
			return item.getDrug() != null ? item.getDrug().getUuid() : item.getUuid();
		}
		return line.getBillableService() != null ? line.getBillableService().getUuid() : null;
	}
	
	static String typeLabel(BillLineItem line) {
		if (line.getItem() != null) {
			return "drug from stock";
		}
		BillableService s = line.getBillableService();
		return s != null && s.getServiceType() != null ? s.getServiceType().getDisplayString() : "no service type";
	}
	
	static String medihealthId(Patient patient) {
		if (patient == null) {
			return null;
		}
		PatientIdentifierType type = Context.getPatientService().getPatientIdentifierTypeByUuid(MEDIHEALTH_ID_TYPE);
		PatientIdentifier id = type != null ? patient.getPatientIdentifier(type) : null;
		return id != null ? StringUtils.trimToNull(id.getIdentifier()) : null;
	}
	
	static String phone(Patient patient) {
		if (patient == null) {
			return "";
		}
		for (String type : new String[] { PHONE_ATTRIBUTE, NEXT_OF_KIN_PHONE_ATTRIBUTE }) {
			PersonAttribute a = patient.getAttribute(type);
			String digits = a == null ? "" : StringUtils.defaultString(a.getValue()).replaceAll("[^0-9+]", "");
			if (!digits.isEmpty()) {
				return digits;
			}
		}
		return "";
	}
	
	static String plain(BigDecimal v) {
		return v.stripTrailingZeros().toPlainString();
	}
	
	/** Whole amounts as integers (Pay1's examples use 100, not 100.00). */
	static Object number(BigDecimal v) {
		BigDecimal s = v.stripTrailingZeros();
		return s.scale() <= 0 ? (Object) s.longValueExact() : s;
	}
}

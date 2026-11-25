package org.openmrs.module.billing.pay1;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.GlobalProperty;
import org.openmrs.Location;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MediHealth (2026-11-25): how MediHealth bills are described to Pay1.
 * <ul>
 * <li>departments: our service type (concept uuid of the billable service's type, or
 * {@link #STOCK_ITEM} for drugs billed from a stock item, or {@link #NO_TYPE}) → the Pay1
 * department name</li>
 * <li>terminals: PHC location uuid → the Pay1 terminal ID of that PHC</li>
 * <li>default department: used for a service type that is not listed (empty = such a line is a
 * problem)</li>
 * <li>channel name: Pay1's channelName for our bills (the docs' EMR example uses ECLINIC)</li>
 * </ul>
 * Kept as JSON in global properties.
 */
public class Pay1Mapping {
	
	public static final String DEPARTMENTS = "billing.pay1.departmentMap";
	
	public static final String TERMINALS = "billing.pay1.terminalMap";
	
	public static final String DEFAULT_DEPARTMENT = "billing.pay1.defaultDepartment";
	
	public static final String CHANNEL_NAME = "billing.pay1.channelName";
	
	public static final String STOCK_ITEM = "STOCK_ITEM";
	
	public static final String NO_TYPE = "NO_TYPE";
	
	public static final String DEFAULT_CHANNEL = "ECLINIC";
	
	private static final ObjectMapper JSON = new ObjectMapper();
	
	private Map<String, String> departments = new LinkedHashMap<>();
	
	private Map<String, String> terminals = new LinkedHashMap<>();
	
	private String defaultDepartment;
	
	private String channelName = DEFAULT_CHANNEL;
	
	public static Pay1Mapping load() {
		AdministrationService as = Context.getAdministrationService();
		Pay1Mapping m = new Pay1Mapping();
		m.departments = readMap(as.getGlobalProperty(DEPARTMENTS));
		m.terminals = readMap(as.getGlobalProperty(TERMINALS));
		m.defaultDepartment = StringUtils.trimToNull(as.getGlobalProperty(DEFAULT_DEPARTMENT));
		m.channelName = StringUtils.defaultIfBlank(as.getGlobalProperty(CHANNEL_NAME), DEFAULT_CHANNEL).trim();
		return m;
	}
	
	public void save() {
		try {
			set(DEPARTMENTS, JSON.writeValueAsString(departments), "Pay1: our service type -> Pay1 department (JSON)");
			set(TERMINALS, JSON.writeValueAsString(terminals), "Pay1: PHC location uuid -> Pay1 terminal ID (JSON)");
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		set(DEFAULT_DEPARTMENT, defaultDepartment, "Pay1 department for service types that are not mapped");
		set(CHANNEL_NAME, channelName, "Pay1 channelName sent with MediHealth bills");
	}
	
	/** The Pay1 department for a service type key, or the default; null when neither is set. */
	public String departmentFor(String typeKey) {
		String d = departments.get(typeKey);
		return StringUtils.isNotBlank(d) ? d : defaultDepartment;
	}
	
	/** The terminal of this location or of the nearest parent location that has one. */
	public String terminalFor(Location location) {
		for (Location l = location; l != null; l = l.getParentLocation()) {
			String t = terminals.get(l.getUuid());
			if (StringUtils.isNotBlank(t)) {
				return t;
			}
		}
		return null;
	}
	
	private static Map<String, String> readMap(String json) {
		Map<String, String> out = new LinkedHashMap<>();
		if (StringUtils.isBlank(json)) {
			return out;
		}
		try {
			Map<String, String> parsed = JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
			for (Map.Entry<String, String> e : parsed.entrySet()) {
				if (StringUtils.isNotBlank(e.getKey()) && StringUtils.isNotBlank(e.getValue())) {
					out.put(e.getKey().trim(), e.getValue().trim());
				}
			}
		}
		catch (IOException e) {
			// a hand-edited, broken value: behave as "nothing mapped" rather than break billing
		}
		return out;
	}
	
	private static void set(String name, String value, String description) {
		AdministrationService as = Context.getAdministrationService();
		GlobalProperty gp = as.getGlobalPropertyObject(name);
		if (gp == null) {
			gp = new GlobalProperty(name, null, description);
		}
		gp.setPropertyValue(StringUtils.trimToEmpty(value));
		as.saveGlobalProperty(gp);
	}
	
	public Map<String, String> getDepartments() {
		return departments;
	}
	
	public void setDepartments(Map<String, String> departments) {
		this.departments = clean(departments);
	}
	
	public Map<String, String> getTerminals() {
		return terminals;
	}
	
	public void setTerminals(Map<String, String> terminals) {
		this.terminals = clean(terminals);
	}
	
	public String getDefaultDepartment() {
		return defaultDepartment;
	}
	
	public void setDefaultDepartment(String defaultDepartment) {
		this.defaultDepartment = StringUtils.trimToNull(defaultDepartment);
	}
	
	public String getChannelName() {
		return channelName;
	}
	
	public void setChannelName(String channelName) {
		this.channelName = StringUtils.defaultIfBlank(StringUtils.trimToNull(channelName), DEFAULT_CHANNEL);
	}
	
	private static Map<String, String> clean(Map<String, String> in) {
		Map<String, String> out = new LinkedHashMap<>();
		if (in != null) {
			for (Map.Entry<String, String> e : in.entrySet()) {
				if (StringUtils.isNotBlank(e.getKey()) && StringUtils.isNotBlank(e.getValue())) {
					out.put(e.getKey().trim(), e.getValue().trim());
				}
			}
		}
		return out;
	}
}

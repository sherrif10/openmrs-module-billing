package org.openmrs.module.billing.pay1;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.GlobalProperty;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.util.Security;

/**
 * MediHealth (2026-11-25): the Pay1One connection settings, kept as global properties. The password
 * is stored encrypted with the server's OpenMRS encryption key and is never sent back to a browser.
 */
public class Pay1Settings {
	
	public static final String BASE_URL = "billing.pay1.baseUrl";
	
	public static final String USERNAME = "billing.pay1.username";
	
	public static final String PASSWORD = "billing.pay1.password";
	
	public static final String BILLER_ID = "billing.pay1.billerId";
	
	public static final String DEFAULT_BASE_URL = "https://pay1one.com/api/v1";
	
	private String baseUrl;
	
	private String username;
	
	private String password;
	
	private String billerId;
	
	public static Pay1Settings load() {
		AdministrationService as = Context.getAdministrationService();
		Pay1Settings s = new Pay1Settings();
		s.baseUrl = StringUtils.defaultIfBlank(as.getGlobalProperty(BASE_URL), DEFAULT_BASE_URL);
		s.username = StringUtils.trimToNull(as.getGlobalProperty(USERNAME));
		String stored = StringUtils.trimToNull(as.getGlobalProperty(PASSWORD));
		s.password = stored == null ? null : Security.decrypt(stored);
		s.billerId = StringUtils.trimToNull(as.getGlobalProperty(BILLER_ID));
		return s;
	}
	
	/** Saves these settings. A blank password keeps the saved one. */
	public void save() {
		set(BASE_URL, baseUrl, "Pay1One API address, e.g. https://pay1one.com/api/v1");
		set(USERNAME, username, "Pay1One API username (Basic authentication)");
		set(BILLER_ID, billerId, "Pay1One billerID sent with every bill");
		if (StringUtils.isNotBlank(password)) {
			set(PASSWORD, Security.encrypt(password), "Pay1One API password, encrypted with the OpenMRS encryption key");
		}
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
	
	/** @return null when usable, otherwise what is missing or wrong, in plain words */
	public String problem() {
		if (StringUtils.isBlank(baseUrl) || !baseUrl.trim().toLowerCase().startsWith("https://")) {
			return "The Pay1 address must start with https://";
		}
		if (StringUtils.isBlank(username)) {
			return "The Pay1 username is missing";
		}
		if (StringUtils.isBlank(password)) {
			return "The Pay1 password is missing";
		}
		return null;
	}
	
	/** The merchant code: the part of the username before "|" (AVPLTEST in "AVPLTEST|6a0b..."). */
	public String merchantCode() {
		if (username == null) {
			return null;
		}
		int bar = username.indexOf('|');
		return bar > 0 ? username.substring(0, bar) : username;
	}
	
	public String getBaseUrl() {
		return baseUrl;
	}
	
	public void setBaseUrl(String baseUrl) {
		this.baseUrl = StringUtils.removeEnd(StringUtils.trimToNull(baseUrl), "/");
	}
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = StringUtils.trimToNull(username);
	}
	
	public String getPassword() {
		return password;
	}
	
	public void setPassword(String password) {
		this.password = password;
	}
	
	public String getBillerId() {
		return billerId;
	}
	
	public void setBillerId(String billerId) {
		this.billerId = StringUtils.trimToNull(billerId);
	}
}

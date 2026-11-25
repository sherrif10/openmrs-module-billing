package org.openmrs.module.billing.pay1;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MediHealth (2026-11-25): the only place that talks to Pay1One. Basic authentication, short
 * timeouts (a slow Pay1 must never freeze a cashier screen), and a log line per call without the
 * password or the response body.
 */
public class Pay1Client {
	
	private static final Logger log = LoggerFactory.getLogger(Pay1Client.class);
	
	static final int CONNECT_TIMEOUT_MS = 10000;
	
	static final int READ_TIMEOUT_MS = 20000;
	
	private static final ObjectMapper JSON = new ObjectMapper();
	
	private final Pay1Settings settings;
	
	public Pay1Client(Pay1Settings settings) {
		this.settings = settings;
	}
	
	/**
	 * The outcome of one call: either Pay1 answered (httpStatus > 0) or it could not be reached (error
	 * set).
	 */
	public static class Result {
		
		public int httpStatus;
		
		public long millis;
		
		public Map<String, Object> body;
		
		/** Plain-words explanation when the call failed. */
		public String error;
		
		public boolean ok() {
			return error == null && httpStatus >= 200 && httpStatus < 300 && body != null
			        && !Boolean.FALSE.equals(body.get("success"));
		}
		
		public String pay1Message() {
			return body == null || body.get("message") == null ? null : String.valueOf(body.get("message"));
		}
	}
	
	public Result get(String path) {
		return call("GET", path, null);
	}
	
	public Result post(String path, Object jsonBody) {
		return call("POST", path, jsonBody);
	}
	
	private Result call(String method, String path, Object jsonBody) {
		Result r = new Result();
		String problem = settings.problem();
		if (problem != null) {
			r.error = problem;
			return r;
		}
		long start = System.currentTimeMillis();
		HttpURLConnection c = null;
		try {
			URL url = new URL(StringUtils.removeEnd(settings.getBaseUrl(), "/") + path);
			c = (HttpURLConnection) url.openConnection();
			c.setRequestMethod(method);
			c.setConnectTimeout(CONNECT_TIMEOUT_MS);
			c.setReadTimeout(READ_TIMEOUT_MS);
			c.setInstanceFollowRedirects(false);
			c.setRequestProperty("Accept", "application/json");
			String auth = settings.getUsername() + ":" + settings.getPassword();
			c.setRequestProperty("Authorization",
			    "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
			if (jsonBody != null) {
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				try (OutputStream out = c.getOutputStream()) {
					out.write(JSON.writeValueAsBytes(jsonBody));
				}
			}
			r.httpStatus = c.getResponseCode();
			InputStream in = r.httpStatus >= 400 ? c.getErrorStream() : c.getInputStream();
			String text = in == null ? "" : IOUtils.toString(in, StandardCharsets.UTF_8);
			if (StringUtils.isNotBlank(text)) {
				try {
					@SuppressWarnings("unchecked")
					Map<String, Object> parsed = JSON.readValue(text, Map.class);
					r.body = parsed;
				}
				catch (IOException notJson) {
					r.error = "Pay1 answered with something that is not JSON (HTTP " + r.httpStatus + ")";
				}
			}
			if (r.error == null && !r.ok()) {
				r.error = explainStatus(r.httpStatus, r.pay1Message());
			}
		}
		catch (UnknownHostException e) {
			r.error = "The server cannot find " + e.getMessage() + ": check the Pay1 address and the server's internet";
		}
		catch (SocketTimeoutException e) {
			r.error = "Pay1 did not answer in time (" + (CONNECT_TIMEOUT_MS / 1000) + "s to connect, "
			        + (READ_TIMEOUT_MS / 1000) + "s to reply)";
		}
		catch (ConnectException e) {
			r.error = "The server could not connect to Pay1: " + e.getMessage();
		}
		catch (SSLException e) {
			r.error = "Secure (https) connection to Pay1 failed: " + e.getMessage();
		}
		catch (IOException | IllegalArgumentException e) {
			r.error = "Could not reach Pay1: " + e.getMessage();
		}
		finally {
			r.millis = System.currentTimeMillis() - start;
			if (c != null) {
				c.disconnect();
			}
		}
		log.info("Pay1 {} {} -> HTTP {} in {} ms{}", method, path, r.httpStatus, r.millis,
		    r.error == null ? "" : " (" + r.error + ")");
		return r;
	}
	
	static String explainStatus(int status, String pay1Message) {
		String said = pay1Message == null ? "" : " Pay1 said: \"" + pay1Message + "\"";
		switch (status) {
			case 401:
				return "Pay1 refused the username or password." + said;
			case 403:
				return "Pay1 says this account is not allowed to do that." + said;
			case 404:
				return "Not found on Pay1." + said;
			case 409:
				return "Pay1 already has this with different details." + said;
			case 422:
				return "Pay1 rejected the details sent." + said;
			case 429:
				return "Too many requests to Pay1; wait a minute and try again." + said;
			default:
				if (status >= 500) {
					return "Pay1 is having a problem on its side (HTTP " + status + ")." + said;
				}
				if (status >= 300 && status < 400) {
					return "Pay1 redirected the request (HTTP " + status + "): check the Pay1 address." + said;
				}
				return "Pay1 answered HTTP " + status + "." + said;
		}
	}
	
	/** A short summary that is safe to show or log: no password. */
	public static Map<String, Object> summary(Result r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ok", r.ok());
		m.put("httpStatus", r.httpStatus);
		m.put("millis", r.millis);
		m.put("error", r.error);
		m.put("pay1Message", r.pay1Message());
		return m;
	}
}

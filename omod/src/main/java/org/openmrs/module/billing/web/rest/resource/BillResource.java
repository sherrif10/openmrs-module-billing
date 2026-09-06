/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.billing.web.rest.resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Provider;
import org.openmrs.User;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ProviderService;
import org.openmrs.api.context.Context;
import org.openmrs.module.billing.ModuleSettings;
import org.openmrs.module.billing.api.BillService;
import org.openmrs.module.billing.api.ITimesheetService;
import org.openmrs.module.billing.api.base.PagingInfo;
import org.openmrs.module.billing.api.model.Bill;
import org.openmrs.module.billing.api.model.BillLineItem;
import org.openmrs.module.billing.api.model.BillStatus;
import org.openmrs.module.billing.api.model.CashPoint;
import org.openmrs.module.billing.api.model.Payment;
import org.openmrs.module.billing.api.model.Timesheet;
import org.openmrs.module.billing.api.search.BillSearch;
import org.openmrs.module.billing.api.util.RoundingUtil;
import org.openmrs.module.billing.web.base.resource.BaseRestDataResource;
import org.openmrs.module.billing.web.base.resource.PagingUtil;
import org.openmrs.module.billing.web.rest.controller.base.CashierResourceController;
import org.openmrs.module.webservices.rest.web.RequestContext;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.annotation.PropertyGetter;
import org.openmrs.module.webservices.rest.web.annotation.PropertySetter;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.representation.DefaultRepresentation;
import org.openmrs.module.webservices.rest.web.representation.FullRepresentation;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.impl.AlreadyPaged;
import org.openmrs.module.webservices.rest.web.resource.impl.DataDelegatingCrudResource;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.response.ResponseException;
import org.springframework.web.client.RestClientException;

/**
 * REST resource representing a {@link Bill}.
 */
@Resource(name = RestConstants.VERSION_1 + CashierResourceController.BILLING_NAMESPACE
        + "/bill", supportedClass = Bill.class, supportedOpenmrsVersions = { "2.0 - 2.*" })
@Slf4j
public class BillResource extends DataDelegatingCrudResource<Bill> {
	
	@Override
	public DelegatingResourceDescription getRepresentationDescription(Representation rep) {
		if (rep instanceof DefaultRepresentation || rep instanceof FullRepresentation) {
			DelegatingResourceDescription description = new DelegatingResourceDescription();
			description.addProperty("adjustedBy", Representation.REF);
			description.addProperty("billAdjusted", Representation.REF);
			description.addProperty("cashPoint", Representation.REF);
			description.addProperty("cashier", Representation.REF);
			description.addProperty("dateCreated");
			description.addProperty("lineItems");
			description.addProperty("patient", Representation.REF);
			description.addProperty("payments", Representation.FULL);
			description.addProperty("receiptNumber");
			description.addProperty("status");
			description.addProperty("adjustmentReason");
			// Bill.getTotal() has always existed on the model but was never published, so the O3
			// billing frontend read undefined and rendered "NGN undefined" / "NGN NaN" on invoices.
			// Upstream exposes this from module version 2.3.0; matching that field name here.
			description.addProperty("total");
			description.addProperty("amountAfterDiscount");
			description.addProperty("uuid");
			return description;
		}
		return null;
	}
	
	@Override
	public DelegatingResourceDescription getCreatableProperties() {
		DelegatingResourceDescription description = getRepresentationDescription(new DefaultRepresentation());
		// "visit" must be whitelisted here or RESTWS rejects the request with
		// "Some properties are not allowed to be set: visit" before any setter runs. It is accepted
		// and discarded by setVisitIgnored; see that method for why this version cannot persist it.
		description.addProperty("visit");
		return description;
	}
	
	@PropertySetter("lineItems")
	public void setBillLineItems(Bill instance, List<BillLineItem> lineItems) {
		if (instance.getLineItems() == null) {
			instance.setLineItems(new ArrayList<>(lineItems.size()));
		}
		BaseRestDataResource.syncCollection(instance.getLineItems(), lineItems);
		for (BillLineItem item : instance.getLineItems()) {
			item.setBill(instance);
		}
	}
	
	@PropertySetter("payments")
	public void setBillPayments(Bill instance, Set<Payment> payments) {
		if (instance.getPayments() == null) {
			instance.setPayments(new HashSet<Payment>(payments.size()));
		}
		BaseRestDataResource.syncCollection(instance.getPayments(), payments);
		for (Payment payment : instance.getPayments()) {
			instance.addPayment(payment);
		}
	}
	
	@PropertySetter("billAdjusted")
	public void setBillAdjusted(Bill instance, Bill billAdjusted) {
		billAdjusted.addAdjustedBy(instance);
		instance.setBillAdjusted(billAdjusted);
	}
	
	@PropertySetter("status")
	public void setBillStatus(Bill instance, BillStatus status) {
		if (instance.getStatus() == null) {
			instance.setStatus(status);
		} else if (instance.getStatus() == BillStatus.PENDING && status == BillStatus.POSTED) {
			instance.setStatus(status);
		}
		if (status == BillStatus.POSTED) {
			RoundingUtil.handleRoundingLineItem(instance);
		}
	}
	
	@PropertySetter("adjustmentReason")
	public void setAdjustReason(Bill instance, String adjustReason) {
		if (instance.getBillAdjusted().getUuid() != null) {
			instance.getBillAdjusted().setAdjustmentReason(adjustReason);
		}
	}
	
	/**
	 * Accepts and discards "visit". Bill/visit association was added upstream in module version 2.3.0
	 * and needs a cashier_bill.visit column that this version does not have. The O3 billing frontend
	 * sends it regardless. This setter discards the value; see getVisitIgnored below, which is what
	 * actually allows the request through. The visit link is simply not persisted, exactly as before
	 * the frontend started sending it.
	 */
	@PropertySetter("visit")
	public void setVisitIgnored(Bill instance, Object visit) {
		if (visit != null) {
			log.debug("Ignoring 'visit' on bill create; not supported before module version 2.3.0");
		}
	}
	
	/**
	 * Paired with {@link #setVisitIgnored}, and required for it to ever run. RESTWS
	 * BaseDelegatingResource.setConvertedProperties reads each submitted property's current value via
	 * getProperty before setting it, and getProperty throws "ConversionException: Unknown property
	 * 'visit'" for a property that has no getter - failing the whole request with a 400 before the
	 * setter is reached. Returning null here lets that read succeed so the no-op setter can discard the
	 * value. Verified against RESTWS 2.50.0.
	 */
	@PropertyGetter("visit")
	public Object getVisitIgnored(Bill instance) {
		return null;
	}
	
	@Override
	public Bill save(Bill bill) {
		//TODO: Test all the ways that this could fail
		
		if (bill.getId() == null) {
			if (bill.getCashier() == null) {
				Provider cashier = getCurrentCashier();
				if (cashier == null) {
					throw new RestClientException("Couldn't find Provider for the current user ("
					        + Context.getAuthenticatedUser().getUsername() + ")");
				}
				
				bill.setCashier(cashier);
			}
			
			if (bill.getCashPoint() == null) {
				loadBillCashPoint(bill);
			}
			
			// Now that all attributes have been set (i.e., payments and bill status) we can check to see if the bill
			// is fully paid.
			bill.synchronizeBillStatus();
			if (bill.getStatus() == null) {
				bill.setStatus(BillStatus.PENDING);
			}
		}
		
		return Context.getService(BillService.class).saveBill(bill);
	}
	
	@Override
	protected AlreadyPaged<Bill> doSearch(RequestContext context) {
		BillSearch billSearch = buildBillSearchFromRequest(context);
		PagingInfo pagingInfo = PagingUtil.getPagingInfoFromContext(context);
		
		BillService service = Context.getService(BillService.class);
		List<Bill> result = service.getBills(billSearch, pagingInfo);
		
		return new AlreadyPaged<>(context, result, pagingInfo.hasMoreResults(), pagingInfo.getTotalRecordCount());
	}
	
	/**
	 * Gets a bill by UUID
	 *
	 * @param uniqueId The bill UUID.
	 * @return The bill with the specified UUID without voided line items.
	 */
	@Override
	public Bill getByUniqueId(String uniqueId) {
		if (StringUtils.isBlank(uniqueId)) {
			return null;
		}
		
		return Context.getService(BillService.class).getBillByUuid(uniqueId);
	}
	
	@Override
	protected void delete(Bill bill, String s, RequestContext requestContext) throws ResponseException {
		Context.getService(BillService.class).voidBill(bill, s);
	}
	
	@Override
	public void purge(Bill bill, RequestContext requestContext) throws ResponseException {
		Context.getService(BillService.class).purgeBill(bill);
	}
	
	@Override
	public Bill newDelegate() {
		return new Bill();
	}
	
	private Provider getCurrentCashier() {
		User currentUser = Context.getAuthenticatedUser();
		ProviderService service = Context.getProviderService();
		Collection<Provider> providers = service.getProvidersByPerson(currentUser.getPerson());
		if (!providers.isEmpty()) {
			return providers.iterator().next();
		}
		return null;
	}
	
	private void loadBillCashPoint(Bill bill) {
		ITimesheetService service = Context.getService(ITimesheetService.class);
		Timesheet timesheet = service.getCurrentTimesheet(bill.getCashier());
		if (timesheet == null) {
			AdministrationService adminService = Context.getAdministrationService();
			boolean timesheetRequired;
			try {
				timesheetRequired = Boolean
				        .parseBoolean(adminService.getGlobalProperty(ModuleSettings.TIMESHEET_REQUIRED_PROPERTY));
			}
			catch (Exception e) {
				timesheetRequired = false;
			}
			
			if (timesheetRequired) {
				throw new RestClientException("A current timesheet does not exist for cashier " + bill.getCashier());
			} else if (bill.getBillAdjusted() != null) {
				// If this is an adjusting bill, copy cash point from billAdjusted
				bill.setCashPoint(bill.getBillAdjusted().getCashPoint());
			} else {
				throw new RestClientException("Cash point cannot be null!");
			}
		} else {
			CashPoint cashPoint = timesheet.getCashPoint();
			if (cashPoint == null) {
				throw new RestClientException("No cash points defined for the current timesheet!");
			}
			bill.setCashPoint(cashPoint);
		}
	}
	
	private BillSearch buildBillSearchFromRequest(RequestContext context) {
		BillSearch billSearch = new BillSearch();
		
		String patientUuid = context.getRequest().getParameter("patientUuid");
		if (StringUtils.isNotBlank(patientUuid)) {
			billSearch.setPatientUuid(patientUuid);
		}
		
		String patientName = context.getRequest().getParameter("patientName");
		if (StringUtils.isNotBlank(patientName)) {
			billSearch.setPatientName(patientName);
		}
		
		String status = context.getRequest().getParameter("status");
		if (StringUtils.isNotBlank(status)) {
			List<BillStatus> statuses = Arrays.stream(status.split(",")).map(String::trim).filter(StringUtils::isNotBlank)
			        .map(s -> BillStatus.valueOf(s.toUpperCase())).collect(Collectors.toList());
			billSearch.setStatuses(statuses);
		}
		
		String cashPointUuid = context.getRequest().getParameter("cashPointUuid");
		if (StringUtils.isNotBlank(cashPointUuid)) {
			billSearch.setCashPointUuid(cashPointUuid);
		}
		
		String includeAll = context.getRequest().getParameter("includeAll");
		if (StringUtils.isNotBlank(includeAll)) {
			billSearch.setIncludeVoidedLineItems(Boolean.parseBoolean(includeAll));
		}
		
		return billSearch;
	}
}

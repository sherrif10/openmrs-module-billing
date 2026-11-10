package org.openmrs.module.billing.advice;

import org.openmrs.DrugOrder;
import org.openmrs.Location;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Provider;
import org.openmrs.TestOrder;
import org.openmrs.User;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.module.billing.api.BillExemptionService;
import org.openmrs.module.billing.api.BillService;
import org.openmrs.module.billing.api.BillableServiceService;
import org.openmrs.module.billing.api.CashPointService;
import org.openmrs.module.billing.api.ItemPriceService;
import org.openmrs.module.billing.api.evaluator.ExemptionRuleEngine;
import org.openmrs.module.billing.api.model.Bill;
import org.openmrs.module.billing.api.model.BillExemption;
import org.openmrs.module.billing.api.model.BillLineItem;
import org.openmrs.module.billing.api.model.BillStatus;
import org.openmrs.module.billing.api.model.BillableService;
import org.openmrs.module.billing.api.model.BillableServiceStatus;
import org.openmrs.module.billing.api.model.CashPoint;
import org.openmrs.module.billing.api.model.CashierItemPrice;
import org.openmrs.module.billing.api.model.ExemptionType;
import org.openmrs.module.billing.api.search.BillableServiceSearch;
import org.openmrs.module.stockmanagement.api.StockManagementService;
import org.openmrs.module.stockmanagement.api.model.StockItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.AfterReturningAdvice;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MediHealth patch of billing 2.1.4 (2026-10-27): 1) a bill made from an order goes to a cash point at the order's
 * location (Pharmacy for drugs, Laboratory for tests) instead of the first cash point of the system; 2) a drug with
 * no stock item is billed through the enabled billable service of its concept, as test orders are.
 */
public class GenerateBillFromOrderAdvice implements AfterReturningAdvice {

	private static final Logger log = LoggerFactory.getLogger(GenerateBillFromOrderAdvice.class);

	final BillService billService = Context.getService(BillService.class);

	final StockManagementService stockService = Context.getService(StockManagementService.class);

	final ItemPriceService priceService = Context.getService(ItemPriceService.class);

	final CashPointService cashPointService = Context.getService(CashPointService.class);

	final ExemptionRuleEngine exemptionRuleEngine = Context.getRegisteredComponent("ruleEngine", ExemptionRuleEngine.class);

	final BillExemptionService billExemptionService = Context.getService(BillExemptionService.class);

	/**
	 * This is called immediately an order is saved
	 */
	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		try {
			ProgramWorkflowService workflowService = Context.getProgramWorkflowService();
			if (method.getName().equals("saveOrder") && args.length > 0 && args[0] instanceof Order) {
				Order order = (Order) args[0];

				// Check if the order is a discontinuation, revision, or renewal
				if (order.getAction().equals(Order.Action.DISCONTINUE) || order.getAction().equals(Order.Action.REVISE)
				        || order.getAction().equals(Order.Action.RENEW)) {
					// Do nothing for these actions
					return;
				}

				Patient patient = order.getPatient();
				String cashierUUID = Context.getAuthenticatedUser().getUuid();

				if (order instanceof DrugOrder) {
					DrugOrder drugOrder = (DrugOrder) order;
					Integer drugID = drugOrder.getDrug() != null ? drugOrder.getDrug().getDrugId() : 0;
					double drugQuantity = drugOrder.getQuantity() != null ? drugOrder.getQuantity() : 0.0;
					List<StockItem> stockItems = stockService.getStockItemByDrug(drugID);

					if (!stockItems.isEmpty()) {
						// check from the list for all exemptions
						boolean isExempted = checkIfOrderIsExempted(workflowService, order, ExemptionType.COMMODITY);
						BillStatus lineItemStatus = isExempted ? BillStatus.EXEMPTED : BillStatus.PENDING;
						addBillItemToBill(order, patient, cashierUUID, stockItems.get(0), null, (int) drugQuantity,
						    order.getDateActivated(), lineItemStatus);
					} else {
						BillableService drugService = findDrugService(drugOrder);
						if (drugService != null) {
							boolean isExempted = checkIfOrderIsExempted(workflowService, order, ExemptionType.COMMODITY);
							BillStatus lineItemStatus = isExempted ? BillStatus.EXEMPTED : BillStatus.PENDING;
							addBillItemToBill(order, patient, cashierUUID, null, drugService,
							    Math.max(1, (int) drugQuantity), order.getDateActivated(), lineItemStatus);
						}
					}
				} else if (order instanceof TestOrder) {
					TestOrder testOrder = (TestOrder) order;
					BillableServiceSearch searchTemplate = new BillableServiceSearch();
					searchTemplate.setConceptUuid(testOrder.getConcept().getUuid());
					searchTemplate.setServiceStatus(BillableServiceStatus.ENABLED);

					BillableServiceService service = Context.getService(BillableServiceService.class);
					List<BillableService> searchResult = service.getBillableServices(searchTemplate, null);
					if (!searchResult.isEmpty()) {
						boolean isExempted = checkIfOrderIsExempted(workflowService, order, ExemptionType.SERVICE);
						BillStatus lineItemStatus = isExempted ? BillStatus.EXEMPTED : BillStatus.PENDING;
						addBillItemToBill(order, patient, cashierUUID, null, searchResult.get(0), 1,
						    order.getDateActivated(), lineItemStatus);
					}
				}
			}
		}
		catch (Exception e) {
			log.error("Error intercepting order before creation: {}", e.getMessage(), e);
		}
	}

	/**
	 * The enabled billable service of the drug's concept: the one named like the drug, otherwise the only one.
	 */
	private BillableService findDrugService(DrugOrder drugOrder) {
		if (drugOrder.getDrug() == null || drugOrder.getDrug().getConcept() == null) {
			return null;
		}
		BillableServiceSearch searchTemplate = new BillableServiceSearch();
		searchTemplate.setConceptUuid(drugOrder.getDrug().getConcept().getUuid());
		searchTemplate.setServiceStatus(BillableServiceStatus.ENABLED);
		List<BillableService> services = Context.getService(BillableServiceService.class)
		        .getBillableServices(searchTemplate, null);
		String drugName = drugOrder.getDrug().getName();
		for (BillableService s : services) {
			if (s.getName() != null && drugName != null && s.getName().trim().equalsIgnoreCase(drugName.trim())) {
				return s;
			}
		}
		return services.size() == 1 ? services.get(0) : null;
	}

	/**
	 * A cash point at the order's location (the encounter's, else the session's): the "Pharmacy" one for drugs, the
	 * "Laboratory" one for tests, else any at that location; the first cash point of the system if the location has none.
	 */
	private CashPoint findCashPoint(Order order) {
		List<CashPoint> cashPoints = cashPointService.getAllCashPoints(false);
		Location location = order.getEncounter() != null ? order.getEncounter().getLocation() : null;
		if (location == null) {
			location = Context.getUserContext().getLocation();
		}
		String wanted = order instanceof DrugOrder ? "pharmacy" : order instanceof TestOrder ? "laboratory" : null;
		CashPoint atLocation = null;
		for (Location l = location; l != null; l = l.getParentLocation()) {
			for (CashPoint cp : cashPoints) {
				if (cp.getLocation() == null || !cp.getLocation().getLocationId().equals(l.getLocationId())) {
					continue;
				}
				if (wanted != null && cp.getName() != null && cp.getName().toLowerCase().endsWith(wanted)) {
					return cp;
				}
				if (atLocation == null) {
					atLocation = cp;
				}
			}
			if (atLocation != null) {
				return atLocation;
			}
		}
		return cashPoints.get(0);
	}

	private boolean checkIfOrderIsExempted(ProgramWorkflowService workflowService, Order order,
	        ExemptionType exemptionType) {
		if (order == null || order.getConcept() == null) {
			return false;
		}
		List<BillExemption> exemptions = billExemptionService.getExemptionsByConcept(order.getConcept(), exemptionType,
		    false);

		if (exemptions == null || exemptions.isEmpty()) {
			return false;
		}

		Map<String, Object> variables = buildVariablesMap(order, workflowService);

		for (BillExemption exemption : exemptions) {
			if (exemptionRuleEngine.isExemptionApplicable(exemption, variables)) {
				return true;
			}
		}

		return false;
	}

	private Map<String, Object> buildVariablesMap(Order order, ProgramWorkflowService workflowService) {
		Map<String, Object> variables = new HashMap<>();

		Patient patient = order.getPatient();
		variables.put("patient", patient);
		// We cannot call getAge() method from Java Script
		if (patient != null) {
			variables.put("patientAge", patient.getAge());
		}

		Map<String, Object> orderData = new HashMap<>();
		orderData.put("uuid", order.getUuid());
		if (order.getConcept() != null) {
			orderData.put("conceptId", order.getConcept().getConceptId());
		}
		variables.put("order", orderData);

		List<PatientProgram> programs = workflowService.getPatientPrograms(patient, null, null, null, new Date(), null,
		    false);
		List<String> activePrograms = programs.stream().filter(PatientProgram::getActive)
		        .map(pp -> pp.getProgram().getName()).collect(Collectors.toList());

		variables.put("activePrograms", activePrograms);

		return variables;
	}

	/**
	 * Adds a bill item to the cashier module
	 *
	 * @param patient
	 * @param cashierUUID
	 */
	public void addBillItemToBill(Order order, Patient patient, String cashierUUID, StockItem stockitem,
	        BillableService service, Integer quantity, Date orderDate, BillStatus lineItemStatus) {
		try {
			// Search for a bill
			Bill activeBill = new Bill();
			activeBill.setPatient(patient);
			activeBill.setStatus(BillStatus.PENDING);
			BillLineItem billLineItem = new BillLineItem();
			List<CashierItemPrice> itemPrices = new ArrayList<>();
			if (stockitem != null) {
				billLineItem.setItem(stockitem);
				itemPrices = priceService.getItemPrice(stockitem);
			} else if (service != null) {
				billLineItem.setBillableService(service);
				itemPrices = priceService.getServicePrice(service);
			}

			if (!itemPrices.isEmpty()) {
				billLineItem.setPrice(itemPrices.get(0).getPrice());
			} else {
				if (stockitem != null && stockitem.getPurchasePrice() != null) {
					billLineItem.setPrice(stockitem.getPurchasePrice());
				} else {
					billLineItem.setPrice(BigDecimal.ZERO);
				}
			}
			billLineItem.setQuantity(quantity);
			billLineItem.setPaymentStatus(lineItemStatus);
			billLineItem.setLineItemOrder(0);
			billLineItem.setOrder(order);

			// Bill
			User user = Context.getAuthenticatedUser();
			List<Provider> providers = new ArrayList<>(Context.getProviderService().getProvidersByPerson(user.getPerson()));

			if (!providers.isEmpty()) {
				activeBill.setCashier(providers.get(0));
				activeBill.setCashPoint(findCashPoint(order));
				activeBill.addLineItem(billLineItem);
				activeBill.setStatus(BillStatus.PENDING);
				billService.saveBill(activeBill);
			} else {
				log.error("User is not a provider");
			}

		}
		catch (Exception ex) {
			log.error("Error sending the bill item: {}", ex.getMessage(), ex);
		}
	}
}

package org.openmrs.module.billing.advice;

import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.Order;
import org.openmrs.Provider;
import org.openmrs.api.context.Context;
import org.openmrs.module.billing.api.BillService;
import org.openmrs.module.billing.api.ItemPriceService;
import org.openmrs.module.billing.api.model.Bill;
import org.openmrs.module.billing.api.model.BillLineItem;
import org.openmrs.module.billing.api.model.BillStatus;
import org.openmrs.module.billing.api.model.BillableService;
import org.openmrs.module.billing.api.model.CashPoint;
import org.openmrs.module.billing.api.model.CashierItemPrice;
import org.openmrs.module.billing.api.model.ExemptionType;
import org.openmrs.module.stockmanagement.api.StockManagementService;
import org.openmrs.module.stockmanagement.api.model.StockItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MediHealth (2026-11-10): the pharmacy's bill for one prescription (the drug orders of one
 * encounter), priced the same way as the automatic order bill (stock item price, else the drug's
 * billable service price) and put on the same PHC cash point. Used by the Dispensing screen:
 * {@link #preview} lists the drugs with prices and total, {@link #generate} makes one PENDING bill
 * for the drugs the pharmacy kept. Removing a drug only leaves it off the bill; the order itself is
 * never changed.
 */
public class PharmacyBilling {
	
	private final GenerateBillFromOrderAdvice advice = new GenerateBillFromOrderAdvice();
	
	/** One line per active drug order of the encounter. */
	public List<Map<String, Object>> preview(Encounter encounter) {
		return withPrivileges(() -> {
			Map<String, String> billed = billedOrders(encounter);
			List<Map<String, Object>> lines = new ArrayList<>();
			for (DrugOrder order : drugOrders(encounter)) {
				Priced p = price(order);
				Map<String, Object> line = new LinkedHashMap<>();
				line.put("order", order.getUuid());
				line.put("orderNumber", order.getOrderNumber());
				line.put("drug",
				    order.getDrug() != null ? order.getDrug().getDisplayName() : order.getConcept().getDisplayString());
				line.put("quantity", quantity(order));
				line.put("quantityUnits",
				    order.getQuantityUnits() != null ? order.getQuantityUnits().getDisplayString() : null);
				line.put("unitPrice", p.price);
				line.put("total", p.price == null ? null : p.price.multiply(BigDecimal.valueOf(quantity(order))));
				line.put("priced", p.price != null);
				line.put("billed", billed.get(order.getUuid()));
				lines.add(line);
			}
			return lines;
		});
	}
	
	/**
	 * One PENDING bill for the given drug orders of the encounter (skipping any already billed). Fails
	 * without changing anything when an order is not a drug order of this encounter or has no price.
	 */
	public Bill generate(Encounter encounter, Collection<String> orderUuids) {
		return withPrivileges(() -> {
			Map<String, String> billed = billedOrders(encounter);
			List<DrugOrder> chosen = new ArrayList<>();
			for (DrugOrder order : drugOrders(encounter)) {
				if (orderUuids.contains(order.getUuid()) && billed.get(order.getUuid()) == null) {
					chosen.add(order);
				}
			}
			if (chosen.isEmpty()) {
				throw new IllegalArgumentException(
				        "Nothing to bill: the selected drugs are already billed or not part of this prescription");
			}
			List<Provider> providers = new ArrayList<>(
			        Context.getProviderService().getProvidersByPerson(Context.getAuthenticatedUser().getPerson()));
			if (providers.isEmpty()) {
				throw new IllegalStateException("Your user account is not a provider, so it cannot create bills");
			}
			Bill bill = new Bill();
			bill.setPatient(encounter.getPatient());
			bill.setCashier(providers.get(0));
			CashPoint cashPoint = advice.findCashPoint(chosen.get(0));
			bill.setCashPoint(cashPoint);
			bill.setStatus(BillStatus.PENDING);
			int position = 0;
			for (DrugOrder order : chosen) {
				Priced p = price(order);
				if (p.price == null) {
					throw new IllegalArgumentException("No price for " + order.getDrug().getDisplayName());
				}
				BillLineItem line = new BillLineItem();
				if (p.stockItem != null) {
					line.setItem(p.stockItem);
				} else {
					line.setBillableService(p.service);
				}
				line.setPrice(p.price);
				line.setQuantity(quantity(order));
				boolean exempted = advice.checkIfOrderIsExempted(Context.getProgramWorkflowService(), order,
				    ExemptionType.COMMODITY);
				line.setPaymentStatus(exempted ? BillStatus.EXEMPTED : BillStatus.PENDING);
				line.setLineItemOrder(position++);
				line.setOrder(order);
				bill.addLineItem(line);
			}
			return Context.getService(BillService.class).saveBill(bill);
		});
	}
	
	/** Active (not discontinued, not voided) NEW/REVISE drug orders of the encounter. */
	private List<DrugOrder> drugOrders(Encounter encounter) {
		List<DrugOrder> out = new ArrayList<>();
		for (Order o : encounter.getOrders()) {
			// a bill line loads its order as a lazy Order proxy; unwrap it, or a DrugOrder would not look like one
			Order order = org.openmrs.api.db.hibernate.HibernateUtil.getRealObjectFromProxy(o);
			if (order instanceof DrugOrder && !order.getVoided() && order.getAction() != Order.Action.DISCONTINUE
			        && order.getDateStopped() == null) {
				out.add((DrugOrder) order);
			}
		}
		out.sort((a, b) -> a.getOrderId().compareTo(b.getOrderId()));
		return out;
	}
	
	/**
	 * order uuid -> receipt number of a bill (not voided) that already bills it: a line linked to the
	 * order, or - for bills made before the line's order link was saved (automatic drug bills up to 10
	 * Nov 2026) - an unlinked line of the same patient for the same stock item / billable service,
	 * created on or after the order.
	 */
	private Map<String, String> billedOrders(Encounter encounter) {
		Map<String, String> billed = new LinkedHashMap<>();
		List<Object[]> unlinked = new ArrayList<>(); // {line, bill}
		for (Bill bill : Context.getService(BillService.class).getBillsByPatientUuid(encounter.getPatient().getUuid(),
		    null)) {
			if (bill.getVoided() || bill.getStatus() == BillStatus.CANCELLED) {
				continue;
			}
			for (BillLineItem line : bill.getLineItems()) {
				if (line.getVoided()) {
					continue;
				}
				if (line.getOrder() != null) {
					billed.put(line.getOrder().getUuid(), bill.getReceiptNumber());
				} else {
					unlinked.add(new Object[] { line, bill });
				}
			}
		}
		for (DrugOrder order : drugOrders(encounter)) {
			if (billed.containsKey(order.getUuid())) {
				continue;
			}
			Priced p = price(order);
			for (Object[] lb : unlinked) {
				BillLineItem line = (BillLineItem) lb[0];
				Bill bill = (Bill) lb[1];
				boolean sameItem = (p.stockItem != null && line.getItem() != null
				        && p.stockItem.getId().equals(line.getItem().getId()))
				        || (p.service != null && line.getBillableService() != null
				                && p.service.getId().equals(line.getBillableService().getId()));
				boolean after = bill.getDateCreated() != null && order.getDateActivated() != null
				        && !bill.getDateCreated().before(new java.util.Date(order.getDateActivated().getTime() - 60000));
				if (sameItem && after) {
					billed.put(order.getUuid(), bill.getReceiptNumber());
					unlinked.remove(lb);
					break;
				}
			}
		}
		return billed;
	}
	
	private static int quantity(DrugOrder order) {
		return order.getQuantity() == null ? 1 : Math.max(1, order.getQuantity().intValue());
	}
	
	private static class Priced {
		
		StockItem stockItem;
		
		BillableService service;
		
		BigDecimal price;
	}
	
	/**
	 * Same rule as the automatic bill: the drug's stock item (its price, else purchase price), else its
	 * billable service.
	 */
	private Priced price(DrugOrder order) {
		Priced p = new Priced();
		ItemPriceService prices = Context.getService(ItemPriceService.class);
		Integer drugId = order.getDrug() != null ? order.getDrug().getDrugId() : 0;
		List<StockItem> stockItems = Context.getService(StockManagementService.class).getStockItemByDrug(drugId);
		if (!stockItems.isEmpty()) {
			p.stockItem = stockItems.get(0);
			List<CashierItemPrice> ip = prices.getItemPrice(p.stockItem);
			if (!ip.isEmpty() && ip.get(0).getPrice() != null) {
				p.price = ip.get(0).getPrice();
			} else if (p.stockItem.getPurchasePrice() != null) {
				p.price = p.stockItem.getPurchasePrice();
			}
			return p;
		}
		p.service = advice.findDrugService(order);
		if (p.service != null) {
			List<CashierItemPrice> sp = prices.getServicePrice(p.service);
			if (!sp.isEmpty()) {
				p.price = sp.get(0).getPrice();
			}
		}
		return p;
	}
	
	private interface Work<T> {
		
		T run();
	}
	
	private static <T> T withPrivileges(Work<T> work) {
		for (String privilege : GenerateBillFromOrderAdvice.BILLING_PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			return work.run();
		}
		finally {
			for (String privilege : GenerateBillFromOrderAdvice.BILLING_PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
}

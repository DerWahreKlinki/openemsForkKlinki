package io.openems.edge.ess.energyprice;

import static io.openems.common.test.TestUtils.createDummyClock;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.CAPACITY_MISSING;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.CHARGE_GRID_ENERGY;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.CHARGE_GRID_POWER;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.CHARGE_PV_POWER;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.PRICE_MISSING;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.STORED_ENERGY;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.STORED_GRID_ENERGY;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.STORED_PV_ENERGY;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.TIMEDATA_MISSING;
import static io.openems.edge.ess.energyprice.EssEnergyPrice.ChannelId.UNPRICED_ENERGY;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.sum.DummySum;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.test.DummyHybridEss;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;
import io.openems.edge.timedata.test.DummyTimedata;

public class EssEnergyPriceImplTest {

	private static final String ID = "essEnergyPrice0";
	private static final String ESS = "ess0";
	private static final String PRICES = "ctrlToU1";
	private static final double DELTA = 0.01;

	private static void assertPrice(EssEnergyPriceImpl sut, Double expected) {
		final var price = sut.getPriceChannel().getNextValue().get();
		if (expected == null) {
			assertNull(price);
		} else {
			assertEquals(expected, price, DELTA);
		}
	}

	@Test
	public void pricesTheChargedEnergyTest() throws Exception {
		final var clock = createDummyClock();
		final var sut = new EssEnergyPriceImpl(Runnable::run);
		final var ess = new DummyHybridEss(ESS);
		final var prices = new DummyPriceSource(PRICES);
		final var sum = new DummySum();
		new ComponentTest(sut) //
				.addReference("componentManager", new DummyComponentManager(clock) //
						.addComponent(ess).addComponent(prices)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("sum", sum) //
				.addReference("timedata", new DummyTimedata("timedata0")) //
				.addReference("ess", ess) //
				.addComponent(ess) //
				.addComponent(prices) //
				.addComponent(sum) //
				.activate(MyConfig.create() //
						.setId(ID) //
						.setEssId(ESS) //
						.setPriceChannel(PRICES + "/QuarterlyPrices") //
						.setFeedInTariff(7) //
						.setEfficiency(100) //
						.build()) //
				.next(new TestCase("idle: the content is there, its price is unknown") //
						.input(ESS, SymmetricEss.ChannelId.CAPACITY, 10000) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 50) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, 0) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input(PRICES, DummyPriceSource.ChannelId.QUARTERLY_PRICES, 300.) //
						.output(STORED_ENERGY, 5000) //
						.output(UNPRICED_ENERGY, 5000) //
						.output(CHARGE_GRID_POWER, 0) //
						.output(CHARGE_PV_POWER, 0) //
						.output(PRICE_MISSING, false) //
						.onAfterControllersCallbacks(() -> assertPrice(sut, null))) //
				.next(new TestCase("charging from the grid for 15 min at 30 ct") //
						.timeleap(clock, 15, MINUTES) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, -4000) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 4000) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 60) //
						.output(CHARGE_GRID_POWER, 4000) //
						.output(CHARGE_PV_POWER, 0) //
						.output(STORED_ENERGY, 6000) //
						.output(STORED_GRID_ENERGY, 1000) //
						.output(STORED_PV_ENERGY, 0) //
						.output(UNPRICED_ENERGY, 5000) //
						.onAfterControllersCallbacks(() -> assertPrice(sut, 300.))) //
				.next(new TestCase("charging from PV with a house load: 1 kW of 4 kW come from the grid") //
						.timeleap(clock, 15, MINUTES) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, -4000) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 1000) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 70) //
						.output(CHARGE_GRID_POWER, 1000) //
						.output(CHARGE_PV_POWER, 3000) //
						.output(CHARGE_GRID_ENERGY, 1000L) //
						.output(STORED_ENERGY, 7000) //
						.output(STORED_GRID_ENERGY, 1250) //
						.output(STORED_PV_ENERGY, 750) //
						// (1000 * 300 + 250 * 300 + 750 * 70) / 2000
						.onAfterControllersCallbacks(() -> assertPrice(sut, 213.75))) //
				.next(new TestCase("discharging keeps the price") //
						.timeleap(clock, 15, MINUTES) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, 2000) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 65) //
						.output(CHARGE_GRID_POWER, 0) //
						.output(CHARGE_GRID_ENERGY, 1250L) //
						.output(STORED_ENERGY, 6500) //
						.output(STORED_GRID_ENERGY, 1161) //
						.output(STORED_PV_ENERGY, 696) //
						.output(UNPRICED_ENERGY, 4643) //
						.onAfterControllersCallbacks(() -> assertPrice(sut, 213.75))) //
				.next(new TestCase("a clock jump counts nothing") //
						.timeleap(clock, 2, HOURS) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, -4000) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 4000) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 65) //
						.output(CHARGE_GRID_POWER, 4000) //
						.output(STORED_ENERGY, 6500) //
						.output(STORED_GRID_ENERGY, 1161) //
						.onAfterControllersCallbacks(() -> assertPrice(sut, 213.75))) //
				.next(new TestCase("the price channel is empty for a moment: the last price stands in") //
						.timeleap(clock, 15, MINUTES) //
						.input(PRICES, DummyPriceSource.ChannelId.QUARTERLY_PRICES, null) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 75) //
						.output(STORED_ENERGY, 7500) //
						.output(STORED_GRID_ENERGY, 2161) //
						.output(PRICE_MISSING, false) //
						// 1857 Wh priced at 213.75 plus 1000 Wh at 300
						.onAfterControllersCallbacks(() -> assertPrice(sut,
								(1857.143 * 213.75 + 1000 * 300) / 2857.143))) //
				.deactivate();
	}

	@Test
	public void lossesRaiseThePriceTest() throws Exception {
		final var clock = createDummyClock();
		final var sut = new EssEnergyPriceImpl(Runnable::run);
		final var ess = new DummyHybridEss(ESS);
		final var prices = new DummyPriceSource(PRICES);
		final var sum = new DummySum();
		new ComponentTest(sut) //
				.addReference("componentManager", new DummyComponentManager(clock) //
						.addComponent(ess).addComponent(prices)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("sum", sum) //
				.addReference("ess", ess) //
				.addComponent(ess) //
				.addComponent(prices) //
				.addComponent(sum) //
				.activate(MyConfig.create() //
						.setId(ID) //
						.setEssId(ESS) //
						.setPriceChannel(PRICES + "/QuarterlyPrices") //
						.setFeedInTariff(5) //
						.setEfficiency(81) //
						.build()) //
				.next(new TestCase("empty") //
						.input(ESS, SymmetricEss.ChannelId.CAPACITY, 10000) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 0) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, 0) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input(PRICES, DummyPriceSource.ChannelId.QUARTERLY_PRICES, 300.) //
						.output(STORED_ENERGY, 0)) //
				.next(new TestCase("1000 Wh from the grid: 900 Wh arrive, a delivered kWh costs 300 / 0.81") //
						.timeleap(clock, 15, MINUTES) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, -4000) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 4000) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 9) //
						.output(STORED_ENERGY, 900) //
						.output(STORED_GRID_ENERGY, 900) //
						.onAfterControllersCallbacks(() -> assertPrice(sut, 300 / 0.81))) //
				.next(new TestCase("1000 Wh from PV at the feed-in tariff (5 ct)") //
						.timeleap(clock, 15, MINUTES) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 18) //
						.output(STORED_ENERGY, 1800) //
						.output(STORED_PV_ENERGY, 900) //
						.onAfterControllersCallbacks(() -> assertPrice(sut, (300 + 50) / 2 / 0.81))) //
				.deactivate();
	}

	@Test
	public void sharesTheGridPurchaseBetweenChargingEssTest() throws Exception {
		final var clock = createDummyClock();
		final var sut = new EssEnergyPriceImpl(Runnable::run);
		final var ess = new DummyHybridEss(ESS);
		final var other = new DummyManagedSymmetricEss("ess1");
		final var prices = new DummyPriceSource(PRICES);
		final var sum = new DummySum();
		new ComponentTest(sut) //
				.addReference("componentManager", new DummyComponentManager(clock) //
						.addComponent(ess).addComponent(other).addComponent(prices)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("sum", sum) //
				.addReference("ess", ess) //
				.addComponent(ess) //
				.addComponent(other) //
				.addComponent(prices) //
				.addComponent(sum) //
				.activate(MyConfig.create() //
						.setId(ID) //
						.setEssId(ESS) //
						.setPriceChannel(PRICES + "/QuarterlyPrices") //
						.build()) //
				.next(new TestCase("both charge 4 kW, the grid delivers 4 kW: 2 kW each") //
						.input(ESS, SymmetricEss.ChannelId.CAPACITY, 10000) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 50) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, -4000) //
						.input("ess1", SymmetricEss.ChannelId.ACTIVE_POWER, -4000) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 4000) //
						.input(PRICES, DummyPriceSource.ChannelId.QUARTERLY_PRICES, 300.) //
						.output(CHARGE_GRID_POWER, 2000) //
						.output(CHARGE_PV_POWER, 2000)) //
				.next(new TestCase("the other one discharges: the grid purchase is ours") //
						.input("ess1", SymmetricEss.ChannelId.ACTIVE_POWER, 1000) //
						.output(CHARGE_GRID_POWER, 4000) //
						.output(CHARGE_PV_POWER, 0)) //
				.next(new TestCase("the AC power of the hybrid ESS does not count, its battery is idle") //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, 0) //
						.input(ESS, SymmetricEss.ChannelId.ACTIVE_POWER, 3000) //
						.output(CHARGE_GRID_POWER, 0) //
						.output(CHARGE_PV_POWER, 0)) //
				.deactivate();
	}

	@Test
	public void rebuildsTheContentFromTheHistoryTest() throws Exception {
		final var clock = createDummyClock();
		final var sut = new EssEnergyPriceImpl(Runnable::run);
		final var ess = new DummyHybridEss(ESS);
		final var prices = new DummyPriceSource(PRICES);
		final var sum = new DummySum();
		final var timedata = new DummyTimedata("timedata0");
		final var power = new ChannelAddress(ESS, "DcDischargePower");
		final var grid = new ChannelAddress("_sum", "GridActivePower");
		final var price = new ChannelAddress(PRICES, "QuarterlyPrices");
		final var soc = new ChannelAddress(ESS, "Soc");
		final var to = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.HOURS);
		// 1000 Wh from the grid at 30 ct, then 1000 Wh from PV at 7 ct, then idle
		var t = to.minusHours(2);
		timedata.add(t, power, -4000);
		timedata.add(t, grid, 4000);
		timedata.add(t, price, 300);
		timedata.add(t, soc, 10);
		t = t.plusMinutes(15);
		timedata.add(t, power, -4000);
		timedata.add(t, grid, 0);
		timedata.add(t, price, 300);
		timedata.add(t, soc, 20);
		t = t.plusMinutes(15);
		timedata.add(t, power, 0);
		timedata.add(t, grid, 500);
		timedata.add(t, price, 350);
		timedata.add(t, soc, 20);
		new ComponentTest(sut) //
				.addReference("componentManager", new DummyComponentManager(clock) //
						.addComponent(ess).addComponent(prices)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("sum", sum) //
				.addReference("timedata", timedata) //
				.addReference("ess", ess) //
				.addComponent(ess) //
				.addComponent(prices) //
				.addComponent(sum) //
				.activate(MyConfig.create() //
						.setId(ID) //
						.setEssId(ESS) //
						.setPriceChannel(PRICES + "/QuarterlyPrices") //
						.setFeedInTariff(7) //
						.setEfficiency(100) //
						.setHistoryDays(1) //
						.build()) //
				.next(new TestCase("without the capacity the history waits and the state shows it") //
						.input(ESS, SymmetricEss.ChannelId.CAPACITY, null) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 20) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, 0) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input(PRICES, DummyPriceSource.ChannelId.QUARTERLY_PRICES, 350.) //
						.output(CAPACITY_MISSING, true) //
						.output(TIMEDATA_MISSING, false) //
						.output(STORED_ENERGY, null) //
						.onAfterControllersCallbacks(() -> assertPrice(sut, null))) //
				.next(new TestCase("the content of the history is priced right away") //
						.output(CAPACITY_MISSING, false) //
						.input(ESS, SymmetricEss.ChannelId.CAPACITY, 10000) //
						.input(ESS, SymmetricEss.ChannelId.SOC, 20) //
						.input(ESS, HybridEss.ChannelId.DC_DISCHARGE_POWER, 0) //
						.input(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input(PRICES, DummyPriceSource.ChannelId.QUARTERLY_PRICES, 350.) //
						.output(STORED_ENERGY, 2000) //
						.output(STORED_GRID_ENERGY, 1000) //
						.output(STORED_PV_ENERGY, 1000) //
						.output(UNPRICED_ENERGY, 0) //
						// (1000 * 300 + 1000 * 70) / 2000
						.onAfterControllersCallbacks(() -> assertPrice(sut, 185.))) //
				.deactivate();
	}
}

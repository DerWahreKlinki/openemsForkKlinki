package io.openems.edge.simulator.clock;

import static java.time.temporal.ChronoUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.test.TimeLeapClock;
import io.openems.edge.common.test.ComponentTest;

public class SimulatorClockImplTest {

	private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

	@Test
	public void scaledClockRunsWithTheFactor() throws Exception {
		final var real = new TimeLeapClock(Instant.parse("2026-10-22T08:00:00Z"), ZONE);
		final var clock = SimulatorClockImpl.createClock(real, 10., "");
		assertEquals(Instant.parse("2026-10-22T08:00:00Z"), clock.instant());
		real.leap(1, MINUTES);
		// one real minute = ten simulated minutes
		assertEquals(Instant.parse("2026-10-22T08:10:00Z"), clock.instant());
		assertEquals(ZONE, clock.getZone());
	}

	@Test
	public void scaledClockStartsAtTheGivenTime() throws Exception {
		final var real = new TimeLeapClock(Instant.parse("2026-10-22T08:00:00Z"), ZONE);
		final var clock = SimulatorClockImpl.createClock(real, 2., "2026-10-22T20:00:00+02:00");
		assertEquals(ZonedDateTime.parse("2026-10-22T20:00:00+02:00").toInstant(), clock.instant());
		real.leap(30, MINUTES);
		assertEquals(ZonedDateTime.parse("2026-10-22T21:00:00+02:00").toInstant(), clock.instant());
	}

	@Test
	public void realTimeIsTheRealClock() throws Exception {
		final var real = new TimeLeapClock(Instant.parse("2026-10-22T08:00:00Z"), ZONE);
		assertSame(real, SimulatorClockImpl.createClock(real, 1., ""));
		assertThrows(OpenemsException.class, () -> SimulatorClockImpl.createClock(real, 0., ""));
		assertThrows(OpenemsException.class, () -> SimulatorClockImpl.createClock(real, 1., "tomorrow"));
	}

	@Test
	public void componentProvidesTheClock() throws Exception {
		final var sut = new SimulatorClockImpl();
		new ComponentTest(sut) //
				.activate(MyConfig.create() //
						.setId("clock0") //
						.setFactor(60.) //
						.build());
		final var before = Instant.now();
		final var simulated = sut.getClock().instant();
		// the simulated clock starts at the real time and does not run behind it
		assertTrue(!simulated.isBefore(before.minusSeconds(1)), "starts now");
		assertTrue(sut.debugLog().length() >= 19, "shows the simulated time");
	}
}

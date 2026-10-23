package io.openems.edge.simulator.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ClockProvider;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Provides the clock of the Edge for simulations: it runs with a factor to the
 * real time, optionally from a given start. The Component-Manager takes it as
 * {@link ClockProvider}; every component that asks the Component-Manager for
 * the clock follows it, e.g. the Time-of-Use-Tariff, the energy optimizer, the
 * predictors and the simulator components. Components that use the system
 * clock, e.g. the Timedata, keep the real time.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Simulator.Clock", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class SimulatorClockImpl extends AbstractOpenemsComponent implements ClockProvider, OpenemsComponent {

	private Clock clock = Clock.systemDefaultZone();

	public SimulatorClockImpl() {
		super(//
				OpenemsComponent.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.clock = createClock(Clock.systemDefaultZone(), config.factor(), config.start());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public Clock getClock() {
		return this.clock;
	}

	@Override
	public String debugLog() {
		return ZonedDateTime.now(this.clock).toLocalDateTime().withNano(0).toString();
	}

	/**
	 * Creates the clock.
	 *
	 * @param real   the real clock
	 * @param factor the factor: simulated seconds per real second
	 * @param start  the simulated time at creation as ISO date-time; empty for the
	 *               real time
	 * @return the clock
	 * @throws OpenemsException on an invalid factor or start
	 */
	protected static Clock createClock(Clock real, double factor, String start) throws OpenemsException {
		if (!(factor > 0)) {
			throw new OpenemsException("Factor must be positive: " + factor);
		}
		final var realStart = Instant.now(real);
		final Instant simulatedStart;
		if (start == null || start.isBlank()) {
			simulatedStart = realStart;
		} else {
			try {
				simulatedStart = ZonedDateTime.parse(start.trim()).toInstant();
			} catch (RuntimeException e) {
				throw new OpenemsException("Start is not an ISO date-time: " + start);
			}
		}
		if (factor == 1. && simulatedStart.equals(realStart)) {
			return real;
		}
		return new ScaledClock(real, realStart, simulatedStart, factor);
	}

	/**
	 * A clock that runs with a factor to another one.
	 */
	protected static class ScaledClock extends Clock {

		private final Clock real;
		private final Instant realStart;
		private final Instant simulatedStart;
		private final double factor;
		private final ZoneId zone;

		protected ScaledClock(Clock real, Instant realStart, Instant simulatedStart, double factor) {
			this(real, realStart, simulatedStart, factor, real.getZone());
		}

		private ScaledClock(Clock real, Instant realStart, Instant simulatedStart, double factor, ZoneId zone) {
			this.real = real;
			this.realStart = realStart;
			this.simulatedStart = simulatedStart;
			this.factor = factor;
			this.zone = zone;
		}

		@Override
		public ZoneId getZone() {
			return this.zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new ScaledClock(this.real, this.realStart, this.simulatedStart, this.factor, zone);
		}

		@Override
		public Instant instant() {
			final var elapsed = Duration.between(this.realStart, Instant.now(this.real));
			return this.simulatedStart.plusMillis(Math.round(elapsed.toMillis() * this.factor));
		}
	}
}

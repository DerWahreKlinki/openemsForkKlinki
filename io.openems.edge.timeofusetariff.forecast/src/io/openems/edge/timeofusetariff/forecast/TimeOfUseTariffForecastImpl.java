package io.openems.edge.timeofusetariff.forecast;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;

import io.openems.common.bridge.http.api.BridgeHttp;
import io.openems.common.bridge.http.api.BridgeHttpFactory;
import io.openems.common.bridge.http.api.HttpError;
import io.openems.common.bridge.http.api.HttpResponse;
import io.openems.common.bridge.http.api.UrlBuilder;
import io.openems.common.bridge.http.time.DelayTimeProvider;
import io.openems.common.bridge.http.time.DelayTimeProviderChain;
import io.openems.common.bridge.http.time.HttpBridgeTimeService;
import io.openems.common.bridge.http.time.HttpBridgeTimeServiceDefinition;
import io.openems.common.timedata.DurationUnit;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;
import io.openems.edge.timeofusetariff.forecast.PriceForecast.Thresholds;
import io.openems.edge.timeofusetariff.forecast.jsonrpc.GetPriceForecastResponse;

/**
 * Price outlook for the next days: the published prices of the Time-of-Use
 * tariff, continued with the EpexPredictor day-ahead forecast. For display
 * only.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "TimeOfUseTariff.Forecast", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				EventConstants.EVENT_TOPIC + "=" + TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
		})
public class TimeOfUseTariffForecastImpl extends AbstractOpenemsComponent
		implements TimeOfUseTariffForecast, OpenemsComponent, EventHandler, ComponentJsonApi {

	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	private final Logger log = LoggerFactory.getLogger(TimeOfUseTariffForecastImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private BridgeHttpFactory httpBridgeFactory;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	private TimeOfUseTariff timeOfUseTariff;

	private Config config;
	private Thresholds thresholds;
	private BridgeHttp httpBridge;
	private HttpBridgeTimeService timeService;
	private HttpBridgeTimeService.TimeEndpoint<HttpResponse<JsonElement>> subscription;

	// The forecast as fetched [Currency/MWh], and the merged outlook
	private volatile NavigableMap<Instant, Double> forecast = Collections.emptyNavigableMap();
	private volatile Instant lastUpdate = null;
	private volatile PriceForecast outlook = null;

	public TimeOfUseTariffForecastImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				TimeOfUseTariffForecast.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.thresholds = new Thresholds(config.ratingLevel2(), config.ratingLevel3(), config.ratingLevel4(),
				config.ratingLevel5());
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "timeOfUseTariff",
				config.timeOfUseTariff_id())) {
			return;
		}
		if (!config.enabled()) {
			return;
		}
		this.httpBridge = this.httpBridgeFactory.get();
		this.timeService = this.httpBridge.createService(HttpBridgeTimeServiceDefinition.INSTANCE);
		if (this.timeService == null) {
			// e.g. the dummy bridge in tests
			return;
		}
		this.subscription = this.timeService.subscribeJsonTime(//
				new FetchDelay(this.componentManager.getClock()), //
				() -> BridgeHttp.create(this.buildUrl()).build(), //
				this::onFetchSuccess, //
				this::onFetchError);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		if (this.timeService != null && this.subscription != null) {
			this.timeService.removeTimeEndpoint(this.subscription);
			this.subscription = null;
		}
		if (this.httpBridge != null) {
			this.httpBridgeFactory.unget(this.httpBridge);
			this.httpBridge = null;
		}
		super.deactivate();
	}

	/**
	 * Builds the request URL from the configuration.
	 *
	 * @return the URL
	 */
	protected String buildUrl() {
		return UrlBuilder.parse(this.config.url()) //
				.withQueryParam("region", this.config.region()) //
				.withQueryParam("surcharge", String.valueOf(this.config.surchargeCt())) //
				.withQueryParam("taxPercent", String.valueOf(this.config.taxPercent())) //
				.withQueryParam("unit", "EUR_PER_KWH") //
				.withQueryParam("hours", String.valueOf(this.config.horizonHours())) //
				.toEncodedString();
	}

	private void onFetchSuccess(HttpResponse<JsonElement> response) throws Exception {
		this._setHttpStatusCode(response.status().code());
		var parsed = PriceForecast.parseEpexPredictor(response.data());
		if (parsed.isEmpty()) {
			throw new IllegalStateException("EpexPredictor returned no prices");
		}
		this.forecast = parsed;
		this.lastUpdate = Instant.now(this.componentManager.getClock());
		this._setLastUpdate(this.lastUpdate.getEpochSecond());
		this._setFetchFailed(false);
		if (this.config.debugMode()) {
			this.logInfo(this.log, "Forecast fetched: " + parsed.size() + " values until "
					+ ISO.format(parsed.lastKey().atZone(this.componentManager.getClock().getZone())));
		}
	}

	private void onFetchError(HttpError error) {
		if (error instanceof HttpError.ResponseError e) {
			this._setHttpStatusCode(e.status.code());
		}
		this._setFetchFailed(true);
		this.logWarn(this.log, "Fetching the price forecast failed: " + error.getMessage());
	}

	/**
	 * Applies a forecast directly; for tests.
	 *
	 * @param forecast   the forecast by start time [Currency/MWh]
	 * @param lastUpdate the fetch time
	 */
	protected void applyForecast(NavigableMap<Instant, Double> forecast, Instant lastUpdate) {
		this.forecast = new TreeMap<>(forecast);
		this.lastUpdate = lastUpdate;
		this._setLastUpdate(lastUpdate.getEpochSecond());
		this._setFetchFailed(false);
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> this.update();
		}
	}

	private void update() {
		var clock = this.componentManager.getClock();
		var now = ZonedDateTime.now(clock);
		var tariff = this.timeOfUseTariff.getPrices();
		var outlook = PriceForecast.merge(now, tariff, this.forecast, this.config.horizonHours());
		this.outlook = outlook;

		var current = outlook.current();
		this._setNoData(current.isEmpty());
		this._setCurrentPrice(current.map(q -> q.price()).orElse(null));
		this._setCurrentRating(current.map(q -> this.thresholds.rate(q.price())).orElse(null));
		this._setForecastHours(outlook.hours());

		var today = outlook.days(this.config.windowHours()).stream() //
				.filter(d -> d.date().equals(now.toLocalDate())) //
				.findFirst();
		this._setTodayMinPrice(today.map(d -> d.min()).orElse(null));
		this._setTodayMaxPrice(today.map(d -> d.max()).orElse(null));

		var window = outlook.cheapestWindow(this.config.windowHours());
		this._setCheapestWindowStart(window.map(w -> ISO.format(w.from())).orElse(null));
		this._setCheapestWindowPrice(window.map(w -> w.price()).orElse(null));
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GetPriceForecastResponse.METHOD, call -> {
			var clock = this.componentManager.getClock();
			var outlook = this.outlook;
			if (outlook == null) {
				outlook = PriceForecast.merge(ZonedDateTime.now(clock), this.timeOfUseTariff.getPrices(),
						this.forecast, this.config.horizonHours());
			}
			return new GetPriceForecastResponse(call.getRequest().getId(),
					outlook.toJson(this.thresholds, this.config.windowHours(), this.lastUpdate, clock.getZone()));
		});
	}

	@Override
	public String debugLog() {
		var outlook = this.outlook;
		if (outlook == null || outlook.isEmpty()) {
			return "No outlook";
		}
		var sb = new StringBuilder();
		outlook.current().ifPresent(q -> sb.append(String.format(Locale.ROOT, "%.1f ct", q.price() / 10)) //
				.append(q.published() ? "" : "*") //
				.append("|Rating:").append(this.thresholds.rate(q.price())));
		sb.append("|Horizon:").append(outlook.hours()).append(" h");
		outlook.cheapestWindow(this.config.windowHours()).ifPresent(w -> sb.append("|Cheapest:")
				.append(w.from().format(DateTimeFormatter.ofPattern("EE HH:mm")))
				.append(String.format(Locale.ROOT, " %.1f ct", w.price() / 10)));
		if (this.config.debugMode()) {
			PriceForecast.tariffMinusForecast(this.timeOfUseTariff.getPrices(), this.forecast).ifPresent(
					d -> sb.append(String.format(Locale.ROOT, "|Tariff-Forecast:%+.1f ct", d / 10)));
			if (this.lastUpdate != null) {
				sb.append("|Age:").append(
						Duration.between(this.lastUpdate, Instant.now(this.componentManager.getClock())).toMinutes())
						.append(" min");
			}
		}
		return sb.toString();
	}

	/**
	 * Fetch schedule: at once on start, then at the full hour; after an error
	 * every five minutes.
	 */
	private static class FetchDelay implements DelayTimeProvider<HttpResponse<JsonElement>> {

		private final Clock clock;

		private FetchDelay(Clock clock) {
			this.clock = clock;
		}

		@Override
		public Delay onFirstRunDelay() {
			return Delay.immediate();
		}

		@Override
		public Delay onErrorRunDelay(HttpError error) {
			return DelayTimeProviderChain.fixedDelay(Duration.ofMinutes(5)) //
					.plusRandomDelay(60, ChronoUnit.SECONDS) //
					.getDelay();
		}

		@Override
		public Delay onSuccessRunDelay(HttpResponse<JsonElement> result) {
			return DelayTimeProviderChain.fixedAtEveryFull(this.clock, DurationUnit.ofHours(1)) //
					.plusRandomDelay(120, ChronoUnit.SECONDS) //
					.getDelay();
		}
	}
}

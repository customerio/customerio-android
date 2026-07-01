package io.customer.android.sample.java_layout.ui.livenotification;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;

import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.ActivityLiveNotificationDemoBinding;
import io.customer.android.sample.java_layout.sdk.CustomerIORepository;
import io.customer.android.sample.java_layout.sdk.LiveNotificationCallback;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.messagingpush.CustomerIOFirebaseMessagingService;
import io.customer.messagingpush.ModuleMessagingPushFCM;
import io.customer.messagingpush.livenotification.LiveNotificationData;

/**
 * Demo activity that simulates templated live-notification updates by sending
 * synthetic push messages through the SDK's actual push handling code path.
 * <p>
 * Each scenario builds a templated FCM data bundle that matches the
 * cross-platform live-activity envelope: top-level lifecycle keys
 * ({@code activity_id}, {@code event}, {@code notification_type}) plus the
 * template fields flattened alongside them (Android does not split static
 * {@code attributes} from dynamic {@code content_state}). The static branding
 * bundle lives in {@code MessagingPushModuleConfig} and is registered once at
 * SDK init.
 */
public class LiveNotificationDemoActivity extends BaseActivity<ActivityLiveNotificationDemoBinding> {

    private static final String DEMO_DELIVERY_TOKEN = "demo-token-live";
    private static final long AUTO_STEP_DELAY_MS = 5000;

    private static final String EVENT_START = "start";
    private static final String EVENT_UPDATE = "update";
    private static final String EVENT_END = "end";

    // activity_type values match the iOS Live Activity identifiers per the cross-platform spec.
    private static final String ACTIVITY_TYPE_DELIVERY_TRACKING = "io.customer.liveactivities.deliverytracking";
    private static final String ACTIVITY_TYPE_FLIGHT_STATUS = "io.customer.liveactivities.flightstatus";
    private static final String ACTIVITY_TYPE_LIVE_SCORE = "io.customer.liveactivities.livescore";
    private static final String ACTIVITY_TYPE_COUNTDOWN_TIMER = "io.customer.liveactivities.countdowntimer";
    private static final String ACTIVITY_TYPE_AUCTION_BID = "io.customer.liveactivities.auctionbid";
    private static final String ACTIVITY_TYPE_UNKNOWN = "io.customer.liveactivities.bogus";
    // Custom (app-rendered) types — rendered by LiveNotificationCallback, not an SDK template.
    private static final String ACTIVITY_TYPE_RIDESHARE = LiveNotificationCallback.ACTIVITY_TYPE_RIDESHARE;
    private static final String ACTIVITY_TYPE_WORKOUT = LiveNotificationCallback.ACTIVITY_TYPE_WORKOUT;

    private enum TemplateChoice {
        DELIVERY_TRACKING, FLIGHT_STATUS, LIVE_SCORE, COUNTDOWN_TIMER, AUCTION_BID,
        CUSTOM_RIDESHARE, CUSTOM_WORKOUT
    }

    // Event the backend campaign listens for; its `activity_type` property selects the template.
    private static final String CAMPAIGN_EVENT = "trigger_live";
    // Dropdown order must match CAMPAIGN_TEMPLATE_LABELS below.
    private static final String[] CAMPAIGN_ACTIVITY_TYPES = {
            ACTIVITY_TYPE_DELIVERY_TRACKING,
            ACTIVITY_TYPE_FLIGHT_STATUS,
            ACTIVITY_TYPE_LIVE_SCORE,
            ACTIVITY_TYPE_COUNTDOWN_TIMER,
            ACTIVITY_TYPE_AUCTION_BID
    };

    private int currentStep = 0;
    private boolean isActive = false;
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private boolean isAutoRunning = false;
    private int selectedCampaignIndex = 0;
    private CustomerIORepository customerIORepository;
    private String lastApiActivityId = null;

    @Override
    protected ActivityLiveNotificationDemoBinding inflateViewBinding() {
        return ActivityLiveNotificationDemoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void injectDependencies() {
        customerIORepository = applicationGraph.getCustomerIORepository();
    }

    @Override
    protected void setupContent() {
        binding.topAppBar.setNavigationOnClickListener(v -> finish());

        binding.startButton.setOnClickListener(v -> start());
        binding.updateButton.setOnClickListener(v -> update());
        binding.endButton.setOnClickListener(v -> end());
        binding.autoButton.setOnClickListener(v -> autoRun());
        binding.unknownActivityTypeButton.setOnClickListener(v -> sendUnknownActivityType());
        binding.apiStartButton.setOnClickListener(v -> startViaApi());
        binding.apiUpdateButton.setOnClickListener(v -> updateViaApi());
        binding.apiEndButton.setOnClickListener(v -> endViaApi());

        setupCampaignDropdown();
        binding.campaignTriggerButton.setOnClickListener(v -> triggerCampaign());

        binding.typeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isActive) {
                autoHandler.removeCallbacksAndMessages(null);
                isActive = false;
                isAutoRunning = false;
                currentStep = 0;
                updateButtonStates();
                binding.statusTextView.setText(R.string.live_notification_status_idle);
            }
        });
    }

    private TemplateChoice getSelectedTemplate() {
        int checkedId = binding.typeRadioGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.radio_flight_status) return TemplateChoice.FLIGHT_STATUS;
        if (checkedId == R.id.radio_live_score) return TemplateChoice.LIVE_SCORE;
        if (checkedId == R.id.radio_countdown_timer) return TemplateChoice.COUNTDOWN_TIMER;
        if (checkedId == R.id.radio_auction_bid) return TemplateChoice.AUCTION_BID;
        if (checkedId == R.id.radio_custom_rideshare) return TemplateChoice.CUSTOM_RIDESHARE;
        if (checkedId == R.id.radio_custom_workout) return TemplateChoice.CUSTOM_WORKOUT;
        return TemplateChoice.DELIVERY_TRACKING;
    }

    private int getStepCount() {
        switch (getSelectedTemplate()) {
            // Delivery: ordered → preparing → out-for-delivery → delivered
            case DELIVERY_TRACKING: return 4;
            // Flight: pre-departure → boarding → in-flight → arrived → delay-red variant
            case FLIGHT_STATUS: return 5;
            case LIVE_SCORE: return 4;
            // Countdown: pre-target → near-target → expired (with message) → post-target dismiss
            case COUNTDOWN_TIMER: return 4;
            // Auction: outbid → winning → outbid again → ended → no-userBidAmount variant
            case AUCTION_BID: return 5;
            // Rideshare (custom RemoteViews): en route → arriving → in trip → dropoff
            case CUSTOM_RIDESHARE: return 4;
            // Workout (builder API): warmup → running → final push → cooldown
            case CUSTOM_WORKOUT: return 4;
            default: return 1;
        }
    }

    // --- Manual controls ---

    private void start() {
        currentStep = 0;
        isActive = true;
        updateButtonStates();
        sendPush(EVENT_START, currentStep);
        updateStatusText();
    }

    private void update() {
        if (!isActive) return;
        currentStep = Math.min(currentStep + 1, getStepCount() - 1);
        updateButtonStates();
        sendPush(EVENT_UPDATE, currentStep);
        updateStatusText();
    }

    private void end() {
        if (!isActive) return;
        isActive = false;
        updateButtonStates();
        sendPush(EVENT_END, getStepCount() - 1);
        binding.statusTextView.setText(R.string.live_notification_status_ended);
    }

    // --- Auto sequence ---

    private void autoRun() {
        if (isAutoRunning) return;
        isAutoRunning = true;
        binding.autoButton.setEnabled(false);
        binding.startButton.setEnabled(false);

        start();

        int totalSteps = getStepCount();
        for (int step = 1; step < totalSteps; step++) {
            autoHandler.postDelayed(this::update, AUTO_STEP_DELAY_MS * step);
        }
        autoHandler.postDelayed(() -> {
            end();
            isAutoRunning = false;
            updateButtonStates();
        }, AUTO_STEP_DELAY_MS * totalSteps);
    }

    // --- Push construction ---

    private void sendPush(String event, int step) {
        switch (getSelectedTemplate()) {
            case DELIVERY_TRACKING: sendDeliveryTracking(event, step); break;
            case FLIGHT_STATUS: sendFlightStatus(event, step); break;
            case LIVE_SCORE: sendLiveScore(event, step); break;
            case COUNTDOWN_TIMER: sendCountdownTimer(event, step); break;
            case AUCTION_BID: sendAuctionBid(event, step); break;
            case CUSTOM_RIDESHARE: sendCustomRideshare(event, step); break;
            case CUSTOM_WORKOUT: sendCustomWorkout(event, step); break;
        }
    }

    private void sendDeliveryTracking(String event, int step) {
        String[] statuses = {
                "Your order has been placed",
                "Your order is being prepared",
                "Your order is out for delivery",
                "Your order has been delivered"
        };
        // Distinct icon per step: ordered → preparing → out-for-delivery → delivered.
        String[] imageKeys = {
                "delivery_ordered",
                "delivery_preparing",
                "delivery_truck",
                "delivery_delivered"
        };
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("orderId", "ABC-1234");
            attributes.put("recipientName", "Mahmoud");

            contentState.put("statusMessage", statuses[step]);
            contentState.put("statusImageKey", imageKeys[step]);
            contentState.put("stepCurrent", step + 1);
            contentState.put("stepTotal", statuses.length);
            contentState.put("estimatedArrival", System.currentTimeMillis() + 30L * 60 * 1000);
            if (step == 2) contentState.put("driverName", "Sam");
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-delivery-tracking", event, ACTIVITY_TYPE_DELIVERY_TRACKING, attributes, contentState));
    }

    private void sendFlightStatus(String event, int step) {
        String[] statuses = {"On time", "Boarding now", "In flight", "Arrived", "Delayed at gate"};
        Double[] progress = {null, null, 0.55, 1.0, null};
        // Step 4 exercises the delay-red accent branch.
        int[] delayMinutes = {0, 0, 0, 0, 25};
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            JSONObject origin = new JSONObject();
            origin.put("code", "JFK");
            origin.put("city", "New York");
            JSONObject destination = new JSONObject();
            destination.put("code", "LAX");
            destination.put("city", "Los Angeles");

            attributes.put("flightNumber", "AA1234");
            attributes.put("origin", origin);
            attributes.put("destination", destination);

            contentState.put("statusMessage", statuses[step]);
            contentState.put("gate", step >= 1 ? "B12" : JSONObject.NULL);
            contentState.put("terminal", step >= 1 ? "4" : JSONObject.NULL);
            contentState.put("scheduledDeparture", System.currentTimeMillis() + 45L * 60 * 1000);
            contentState.put("estimatedArrival", System.currentTimeMillis() + 6L * 60 * 60 * 1000);
            if (progress[step] != null) contentState.put("progressFraction", progress[step]);
            if (delayMinutes[step] > 0) contentState.put("delayMinutes", delayMinutes[step]);
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-flight-status", event, ACTIVITY_TYPE_FLIGHT_STATUS, attributes, contentState));
    }

    private void sendLiveScore(String event, int step) {
        int[] homeScores = {0, 14, 21, 28};
        int[] awayScores = {0, 7, 21, 24};
        String[] periods = {"1st Quarter", "2nd Quarter", "3rd Quarter", "FT"};
        String[] clocks = {"12:00", "5:30", "0:42", null};
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            JSONObject homeTeam = new JSONObject();
            homeTeam.put("name", "Lakers");
            JSONObject awayTeam = new JSONObject();
            awayTeam.put("name", "Celtics");

            attributes.put("homeTeam", homeTeam);
            attributes.put("awayTeam", awayTeam);
            attributes.put("sport", "basketball");
            attributes.put("leagueLogoKey", "league_nba");

            contentState.put("homeScore", homeScores[step]);
            contentState.put("awayScore", awayScores[step]);
            contentState.put("period", periods[step]);
            if (clocks[step] != null) contentState.put("clock", clocks[step]);
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-live-score", event, ACTIVITY_TYPE_LIVE_SCORE, attributes, contentState));
    }

    private void sendCountdownTimer(String event, int step) {
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("title", "Flash Sale");
            attributes.put("heroImageKey", "flash_sale_hero");

            // Step 0: 5 min out. Step 1: 30s out. Step 2: post-target with expired message.
            // Step 3: post-target with NO expiredMessage — exercises cancelImmediately path.
            long now = System.currentTimeMillis();
            long[] offsets = {5 * 60 * 1000L, 30 * 1000L, -1, -1};
            contentState.put("targetDate", offsets[step] >= 0 ? now + offsets[step] : now - 1000);
            contentState.put("statusMessage", "Sale starts in");
            if (step == 2) contentState.put("expiredMessage", "Sale is live!");
            // step == 3: deliberately omit expiredMessage
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-countdown-timer", event, ACTIVITY_TYPE_COUNTDOWN_TIMER, attributes, contentState));
    }

    private void sendAuctionBid(String event, int step) {
        // Step 0: outbid, step 1: winning, step 2: outbid again, step 3: ended
        // Step 4: user has no bid in flight — exercises subtext "no user bid" branch.
        boolean[] highBidder = {false, true, false, false, false};
        String[] currentBids = {"1,200", "1,250", "1,300", "1,300", "1,300"};
        String[] userBids = {"1,150", "1,250", "1,250", "1,250", null};
        String[] statuses = {
                "You've been outbid",
                "You're winning",
                "You've been outbid",
                "Auction ended",
                "You haven't bid yet"
        };
        int[] bidCounts = {7, 8, 9, 9, 9};
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("itemTitle", "Vintage Camera");
            attributes.put("itemImageKey", "auction_camera");
            attributes.put("currencySymbol", "$");

            contentState.put("currentBid", currentBids[step]);
            contentState.put("bidCount", bidCounts[step]);
            contentState.put("endTime", System.currentTimeMillis() + 10L * 60 * 1000);
            contentState.put("statusMessage", statuses[step]);
            contentState.put("isUserHighBidder", highBidder[step]);
            if (userBids[step] != null) contentState.put("userBidAmount", userBids[step]);
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-auction-bid", event, ACTIVITY_TYPE_AUCTION_BID, attributes, contentState));
    }

    // --- Custom (app-rendered) types: rendered by LiveNotificationCallback, not an SDK template ---

    /**
     * Custom type rendered by the host app through a completely custom RemoteViews
     * layout (see {@link LiveNotificationCallback}). The SDK has no template for it.
     */
    private void sendCustomRideshare(String event, int step) {
        String[] statuses = {
                "Heading to your pickup",
                "Arriving now — look for the car",
                "On the way to your destination",
                "You've arrived"
        };
        String[] etas = {"6 min", "1 min", "12 min", "Now"};
        // progress across the 4 stops
        int[] progress = {15, 40, 80, 100};
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("driverName", "Alex");
            attributes.put("vehicle", "Toyota Prius");
            attributes.put("plate", "7XYZ123");

            contentState.put("statusMessage", statuses[step]);
            contentState.put("etaText", etas[step]);
            contentState.put("step", step);
            contentState.put("progress", progress[step]);
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-rideshare", event, ACTIVITY_TYPE_RIDESHARE, attributes, contentState));
    }

    /**
     * Custom type rendered by the host app via the standard NotificationCompat builder
     * API (determinate progress + BigTextStyle + action), requesting promoted-ongoing.
     */
    private void sendCustomWorkout(String event, int step) {
        String[] distances = {"0.4 km", "2.4 km", "4.1 km", "5.0 km"};
        String[] durations = {"02:10", "14:32", "24:48", "30:15"};
        String[] paces = {"5'25\"/km", "6'03\"/km", "6'02\"/km", "6'03\"/km"};
        int[] progress = {8, 48, 82, 100};
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("workoutTitle", "Morning Run");

            contentState.put("distance", distances[step]);
            contentState.put("duration", durations[step]);
            contentState.put("pace", paces[step]);
            contentState.put("step", step);
            contentState.put("progress", progress[step]);
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-workout", event, ACTIVITY_TYPE_WORKOUT, attributes, contentState));
    }

    /**
     * Exercises the public local-start API: the SDK generates the activity id, renders
     * the notification immediately, and registers the instance with the backend.
     */
    /** Demonstrates the typed local-start API: {@code startLiveNotification(LiveNotificationData)}. */
    private void startViaApi() {
        ModuleMessagingPushFCM module = CustomerIORepository.messagingPushModule;
        if (module == null) return;
        LiveNotificationData.DeliveryTracking data = new LiveNotificationData.DeliveryTracking(
                /* orderId */ "API-1001",
                /* statusMessage */ "Out for delivery (started via API)",
                /* recipientName */ "Mahmoud",
                /* driverName */ "Sara",
                /* statusImageKey */ "delivery_truck",
                /* stepCurrent */ 3,
                /* stepTotal */ 4,
                /* estimatedArrival */ System.currentTimeMillis() + 30L * 60 * 1000
        );
        String activityId = module.startLiveNotification(data);
        lastApiActivityId = activityId;
        binding.statusTextView.setText(getString(R.string.live_notification_status_format, "API:" + activityId, 1));
    }

    /**
     * Demonstrates the typed local-update API:
     * {@code updateLiveNotification(activityId, LiveNotificationData)} against the activity
     * started via {@link #startViaApi()}.
     */
    private void updateViaApi() {
        ModuleMessagingPushFCM module = CustomerIORepository.messagingPushModule;
        if (module == null || lastApiActivityId == null) return;
        LiveNotificationData.DeliveryTracking data = new LiveNotificationData.DeliveryTracking(
                /* orderId */ "API-1001",
                /* statusMessage */ "Arriving now (updated via API)",
                /* recipientName */ "Mahmoud",
                /* driverName */ "Sara",
                /* statusImageKey */ "delivery_door",
                /* stepCurrent */ 4,
                /* stepTotal */ 4,
                /* estimatedArrival */ null
        );
        module.updateLiveNotification(lastApiActivityId, data);
        binding.statusTextView.setText(getString(R.string.live_notification_status_format, "API:" + lastApiActivityId, 2));
    }

    /** Demonstrates the local-end API: {@code endLiveNotification(activityId)}. */
    private void endViaApi() {
        ModuleMessagingPushFCM module = CustomerIORepository.messagingPushModule;
        if (module == null || lastApiActivityId == null) return;
        module.endLiveNotification(lastApiActivityId);
        binding.statusTextView.setText(R.string.live_notification_status_ended);
        lastApiActivityId = null;
    }

    // --- On-demand push-to-start registration ---

    // --- Campaign trigger ---

    private void setupCampaignDropdown() {
        String[] labels = {
                getString(R.string.live_notification_type_delivery_tracking),
                getString(R.string.live_notification_type_flight_status),
                getString(R.string.live_notification_type_live_score),
                getString(R.string.live_notification_type_countdown_timer),
                getString(R.string.live_notification_type_auction_bid)
        };
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        binding.campaignTemplateDropdown.setAdapter(adapter);
        binding.campaignTemplateDropdown.setText(labels[selectedCampaignIndex], false);
        binding.campaignTemplateDropdown.setOnItemClickListener(
                (parent, view, position, id) -> selectedCampaignIndex = position);
    }

    /**
     * Tracks the {@code trigger_live} event with the selected {@code activity_type} and a
     * unique {@code timestamp}. A backend campaign listening for this event then pushes the
     * real start/update/end lifecycle through the live-notification path — no synthetic local
     * push here.
     * <p>
     * The {@code timestamp} property is meant to be injected into the {@code activity_id} of
     * every payload via Liquid (e.g. {@code "activity_id": "order-{{ event.timestamp }}"}) so
     * each campaign run gets a fresh activity id. Reusing an activity id across runs would hit
     * the SDK's out-of-order guard (a prior {@code end} freezes that id's high-water timestamp),
     * and the new pushes would be dropped as stale.
     */
    private void triggerCampaign() {
        String activityType = CAMPAIGN_ACTIVITY_TYPES[selectedCampaignIndex];
        Map<String, String> properties = new HashMap<>();
        properties.put("activity_type", activityType);
        // Unique per trigger; used by the campaign to build a fresh activity_id via Liquid.
        properties.put("timestamp", String.valueOf(System.currentTimeMillis()));
        customerIORepository.trackEvent(CAMPAIGN_EVENT, properties);
        Snackbar.make(
                binding.campaignTriggerButton,
                getString(R.string.live_notification_campaign_event_sent, activityType),
                Snackbar.LENGTH_SHORT
        ).show();
    }

    private void sendUnknownActivityType() {
        // Exercises LiveNotificationHandler's "Unknown live notification template" log path.
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        Bundle bundle = buildBundle(
                "demo-unknown-activity-type",
                EVENT_START,
                ACTIVITY_TYPE_UNKNOWN,
                attributes,
                contentState
        );
        fire(bundle);
    }

    private Bundle buildBundle(
            String activityId,
            String event,
            String activityType,
            JSONObject attributes,
            JSONObject contentState
    ) {
        Bundle bundle = new Bundle();
        bundle.putString("CIO-Delivery-ID", UUID.randomUUID().toString());
        bundle.putString("CIO-Delivery-Token", DEMO_DELIVERY_TOKEN);
        bundle.putString("activity_id", activityId);
        bundle.putString("event", event);
        bundle.putString("notification_type", activityType);
        // The backend sends template fields flattened at the envelope top level.
        putFlattened(bundle, attributes);
        putFlattened(bundle, contentState);
        return bundle;
    }

    private void putFlattened(Bundle bundle, JSONObject obj) {
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = obj.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            // Nested objects (origin, homeTeam, …) ride along as JSON strings,
            // matching how FCM delivers non-scalar data values.
            bundle.putString(key, value.toString());
        }
    }

    private void fire(Bundle bundle) {
        RemoteMessage remoteMessage = new RemoteMessage(bundle);
        CustomerIOFirebaseMessagingService.onMessageReceived(this, remoteMessage);
    }

    // --- UI state ---

    private void updateButtonStates() {
        int maxStep = getStepCount() - 1;
        binding.startButton.setEnabled(!isActive && !isAutoRunning);
        binding.updateButton.setEnabled(isActive && currentStep < maxStep);
        binding.endButton.setEnabled(isActive);
        binding.autoButton.setEnabled(!isAutoRunning);
    }

    private void updateStatusText() {
        binding.statusTextView.setText(getString(R.string.live_notification_status_format, getSelectedTemplate().name(), currentStep + 1));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoHandler.removeCallbacksAndMessages(null);
    }
}

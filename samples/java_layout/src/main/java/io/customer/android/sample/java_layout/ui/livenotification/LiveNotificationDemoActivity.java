package io.customer.android.sample.java_layout.ui.livenotification;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.ActivityLiveNotificationDemoBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.messagingpush.CustomerIOFirebaseMessagingService;

/**
 * Demo activity that simulates templated live-notification updates by sending
 * synthetic push messages through the SDK's actual push handling code path.
 * <p>
 * Each scenario builds a templated FCM data bundle: top-level lifecycle keys
 * ({@code activity_id}, {@code event}, {@code template}) plus a {@code payload}
 * JSON string carrying template-specific fields. The static branding bundle
 * lives in {@code MessagingPushModuleConfig} and is registered once at SDK init.
 */
public class LiveNotificationDemoActivity extends BaseActivity<ActivityLiveNotificationDemoBinding> {

    private static final String DEMO_DELIVERY_TOKEN = "demo-token-live";
    private static final long AUTO_STEP_DELAY_MS = 2000;

    private static final String EVENT_START = "start";
    private static final String EVENT_UPDATE = "update";
    private static final String EVENT_END = "end";

    // Templates
    private static final String TEMPLATE_DELIVERY_TRACKING = "delivery_tracking";
    private static final String TEMPLATE_FLIGHT_STATUS = "flight_status";
    private static final String TEMPLATE_LIVE_SCORE = "live_score";
    private static final String TEMPLATE_COUNTDOWN_TIMER = "countdown_timer";
    private static final String TEMPLATE_AUCTION_BID = "auction_bid";

    private enum TemplateChoice {
        DELIVERY_TRACKING, FLIGHT_STATUS, LIVE_SCORE, COUNTDOWN_TIMER, AUCTION_BID
    }

    private int currentStep = 0;
    private boolean isActive = false;
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private boolean isAutoRunning = false;

    @Override
    protected ActivityLiveNotificationDemoBinding inflateViewBinding() {
        return ActivityLiveNotificationDemoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void setupContent() {
        binding.topAppBar.setNavigationOnClickListener(v -> finish());

        binding.startButton.setOnClickListener(v -> start());
        binding.updateButton.setOnClickListener(v -> update());
        binding.endButton.setOnClickListener(v -> end());
        binding.autoButton.setOnClickListener(v -> autoRun());

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
        return TemplateChoice.DELIVERY_TRACKING;
    }

    private int getStepCount() {
        switch (getSelectedTemplate()) {
            case DELIVERY_TRACKING: return 4; // ordered, preparing, on the way, delivered
            case FLIGHT_STATUS: return 4;     // pre-departure, boarding, in-flight, arrived
            case LIVE_SCORE: return 4;        // 4 score updates
            case COUNTDOWN_TIMER: return 3;   // pre-target, near-target, expired
            case AUCTION_BID: return 4;       // outbid, winning, outbid again, ended
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
        }
    }

    private void sendDeliveryTracking(String event, int step) {
        String[] statuses = {
                "Your order has been placed",
                "Your order is being prepared",
                "Your order is out for delivery",
                "Your order has been delivered"
        };
        String[] imageKeys = {"delivery_warehouse", "delivery_warehouse", "delivery_truck", "delivery_door"};
        JSONObject payload = new JSONObject();
        try {
            payload.put("orderId", "ABC-1234");
            payload.put("recipientName", "Mahmoud");
            payload.put("statusMessage", statuses[step]);
            payload.put("statusImageKey", imageKeys[step]);
            payload.put("stepCurrent", step + 1);
            payload.put("stepTotal", statuses.length);
            payload.put("estimatedArrival", System.currentTimeMillis() + 30L * 60 * 1000);
            if (step == 2) payload.put("driverName", "Sam");
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-delivery-tracking", event, TEMPLATE_DELIVERY_TRACKING, payload));
    }

    private void sendFlightStatus(String event, int step) {
        String[] statuses = {"On time", "Boarding now", "In flight", "Arrived"};
        Double[] progress = {null, null, 0.55, 1.0};
        JSONObject payload = new JSONObject();
        try {
            JSONObject origin = new JSONObject();
            origin.put("code", "JFK");
            origin.put("city", "New York");
            JSONObject destination = new JSONObject();
            destination.put("code", "LAX");
            destination.put("city", "Los Angeles");

            payload.put("flightNumber", "AA1234");
            payload.put("origin", origin);
            payload.put("destination", destination);
            payload.put("statusMessage", statuses[step]);
            payload.put("gate", step >= 1 ? "B12" : JSONObject.NULL);
            payload.put("terminal", step >= 1 ? "4" : JSONObject.NULL);
            payload.put("scheduledDeparture", System.currentTimeMillis() + 45L * 60 * 1000);
            payload.put("estimatedArrival", System.currentTimeMillis() + 6L * 60 * 60 * 1000);
            if (progress[step] != null) payload.put("progressFraction", progress[step]);
            if (step == 0) payload.put("delayMinutes", 0); // example: not delayed
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-flight-status", event, TEMPLATE_FLIGHT_STATUS, payload));
    }

    private void sendLiveScore(String event, int step) {
        int[] homeScores = {0, 14, 21, 28};
        int[] awayScores = {0, 7, 21, 24};
        String[] periods = {"1st Quarter", "2nd Quarter", "3rd Quarter", "FT"};
        String[] clocks = {"12:00", "5:30", "0:42", null};
        JSONObject payload = new JSONObject();
        try {
            JSONObject homeTeam = new JSONObject();
            homeTeam.put("name", "Lakers");
            JSONObject awayTeam = new JSONObject();
            awayTeam.put("name", "Celtics");

            payload.put("homeTeam", homeTeam);
            payload.put("awayTeam", awayTeam);
            payload.put("sport", "basketball");
            payload.put("leagueLogoKey", "league_nba");
            payload.put("homeScore", homeScores[step]);
            payload.put("awayScore", awayScores[step]);
            payload.put("period", periods[step]);
            if (clocks[step] != null) payload.put("clock", clocks[step]);
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-live-score", event, TEMPLATE_LIVE_SCORE, payload));
    }

    private void sendCountdownTimer(String event, int step) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("title", "Flash Sale");
            payload.put("heroImageKey", "flash_sale_hero");
            // Step 0: 5 min out. Step 1: 30s out. Step 2: post-target with expired message.
            long now = System.currentTimeMillis();
            long[] offsets = {5 * 60 * 1000L, 30 * 1000L, -1};
            payload.put("targetDate", offsets[step] >= 0 ? now + offsets[step] : now - 1000);
            payload.put("statusMessage", "Sale starts in");
            if (step == 2) payload.put("expiredMessage", "Sale is live!");
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-countdown-timer", event, TEMPLATE_COUNTDOWN_TIMER, payload));
    }

    private void sendAuctionBid(String event, int step) {
        // Step 0: outbid, step 1: winning, step 2: outbid again, step 3: ended
        boolean[] highBidder = {false, true, false, false};
        String[] currentBids = {"1,200", "1,250", "1,300", "1,300"};
        String[] userBids = {"1,150", "1,250", "1,250", "1,250"};
        String[] statuses = {"You've been outbid", "You're winning", "You've been outbid", "Auction ended"};
        int[] bidCounts = {7, 8, 9, 9};
        JSONObject payload = new JSONObject();
        try {
            payload.put("itemTitle", "Vintage Camera");
            payload.put("itemImageKey", "auction_camera");
            payload.put("currencySymbol", "$");
            payload.put("currentBid", currentBids[step]);
            payload.put("bidCount", bidCounts[step]);
            payload.put("endTime", System.currentTimeMillis() + 10L * 60 * 1000);
            payload.put("statusMessage", statuses[step]);
            payload.put("isUserHighBidder", highBidder[step]);
            payload.put("userBidAmount", userBids[step]);
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-auction-bid", event, TEMPLATE_AUCTION_BID, payload));
    }

    private Bundle buildBundle(String activityId, String event, String template, JSONObject payload) {
        Bundle bundle = new Bundle();
        bundle.putString("CIO-Delivery-ID", UUID.randomUUID().toString());
        bundle.putString("CIO-Delivery-Token", DEMO_DELIVERY_TOKEN);
        bundle.putString("activity_id", activityId);
        bundle.putString("event", event);
        bundle.putString("template", template);
        bundle.putString("payload", payload.toString());
        return bundle;
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

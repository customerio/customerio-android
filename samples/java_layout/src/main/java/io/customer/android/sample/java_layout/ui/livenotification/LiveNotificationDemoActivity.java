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
 * Demo activity that simulates live notification updates by sending synthetic
 * push messages through the SDK's actual push handling code path.
 * <p>
 * Select a notification type via radio buttons, then use manual controls
 * (Start / Update / End) or auto-run all steps with a 2-second interval.
 * <p>
 * Payloads follow the iOS-style Live Activity schema: top-level keys
 * ({@code activity_id}, {@code event}, {@code activity_type}) plus two
 * JSON blobs: {@code attributes} (structural/static) and
 * {@code content_state} (dynamic/mutable).
 */
public class LiveNotificationDemoActivity extends BaseActivity<ActivityLiveNotificationDemoBinding> {

    private static final String DEMO_DELIVERY_TOKEN = "demo-token-live";
    private static final long AUTO_STEP_DELAY_MS = 2000;

    // Event values (lifecycle)
    private static final String EVENT_START = "start";
    private static final String EVENT_UPDATE = "update";
    private static final String EVENT_END = "end";

    // Activity types (rendering mode)
    private static final String TYPE_PROGRESS = "progress";
    private static final String TYPE_COUNTDOWN = "countdown";
    private static final String TYPE_TEXT = "text";

    // Shared demo assets
    private static final String DEMO_ICON = "ic_notification";
    // Lorem Picsum — free placeholder image service (https://picsum.photos).
    // Using a fixed image id so the demo displays the same image on every run.
    private static final String DEMO_LARGE_ICON_URL = "https://picsum.photos/id/237/400/400.jpg";
    private static final long DEMO_DISMISS_DELAY_MS = 5000L;

    // Delivery tracking config
    private static final String DELIVERY_ID = "demo-delivery";
    private static final String[] DELIVERY_TITLES = {"Ordered", "Preparing", "On the way", "Delivered"};
    private static final String[] DELIVERY_BODIES = {
            "Your order has been placed",
            "Your order is being prepared",
            "Your order is on the way",
            "Your order has been delivered"
    };
    private static final String[] DELIVERY_SUBTEXTS = {
            "Estimated delivery: 30 min",
            "Estimated delivery: 25 min",
            "Estimated delivery: 10 min",
            "Enjoy your meal!"
    };
    private static final String DELIVERY_SEGMENTS = "[{\"length\":1},{\"length\":1},{\"length\":1},{\"length\":1}]";
    private static final String DELIVERY_COLOR = "#1B5E20";

    // Sports score config
    private static final String SPORTS_ID = "demo-sports";
    private static final String[] SPORTS_TITLES = {"Lakers 98 - Celtics 95", "Lakers 101 - Celtics 97", "Final: Lakers 105 - Celtics 99"};
    private static final String[] SPORTS_BODIES = {"Q4 2:30 remaining", "Q4 1:15 remaining", "Game Over"};
    private static final String[] SPORTS_SUBTEXTS = {"Live", "Live", "Final"};
    private static final String SPORTS_COLOR = "#4A148C";
    private static final String SPORTS_ACTIONS =
            "[{\"label\":\"Open Scorecard\",\"link\":\"sample://sports/game/123\"}]";

    // Parking timer config
    private static final String TIMER_ID = "demo-parking";
    private static final String[] TIMER_TITLES = {"Parking Session", "Parking Session", "Parking Expired"};
    private static final String[] TIMER_BODIES = {"Zone A - Spot 42", "Zone A - Spot 42", "Your session has ended"};
    private static final String[] TIMER_SUBTEXTS = {"Time remaining", "Expiring soon", "Expired"};
    private static final String[] TIMER_COLORS = {"#2E7D32", "#E65100", "#B71C1C"};
    private static final String TIMER_ACTIONS =
            "[{\"label\":\"Extend Parking\",\"link\":\"sample://parking/extend\"}]";

    // Delivery with actions config
    private static final String ACTIONS_ID = "demo-delivery-actions";
    private static final String[] ACTIONS_TITLES = {"Order confirmed", "Preparing", "On the way", "Delivered!"};
    private static final String[] ACTIONS_BODIES = {
            "Restaurant received your order",
            "Your food is being prepared",
            "Driver is heading to you",
            "Enjoy your meal"
    };
    private static final String ACTIONS_SEGMENTS = "[{\"length\":1},{\"length\":1},{\"length\":1},{\"length\":1}]";
    private static final String ACTIONS_COLOR = "#1B5E20";
    private static final String ACTIONS_BUTTONS =
            "[{\"label\":\"View Order\",\"link\":\"sample://order/456\"},{\"label\":\"Get Directions\",\"link\":\"sample://directions\"}]";

    private enum NotificationType { DELIVERY, TIMER, SPORTS, DELIVERY_ACTIONS }

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
            // Reset state when switching type
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

    private NotificationType getSelectedType() {
        int checkedId = binding.typeRadioGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.radio_timer) return NotificationType.TIMER;
        if (checkedId == R.id.radio_sports) return NotificationType.SPORTS;
        if (checkedId == R.id.radio_delivery_actions) return NotificationType.DELIVERY_ACTIONS;
        return NotificationType.DELIVERY;
    }

    private int getStepCount() {
        switch (getSelectedType()) {
            case TIMER: return TIMER_TITLES.length;
            case SPORTS: return SPORTS_TITLES.length;
            case DELIVERY_ACTIONS: return ACTIONS_TITLES.length;
            default: return DELIVERY_TITLES.length;
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
        switch (getSelectedType()) {
            case DELIVERY:
                sendDeliveryPush(event, step);
                break;
            case TIMER:
                sendTimerPush(event, step);
                break;
            case SPORTS:
                sendSportsPush(event, step);
                break;
            case DELIVERY_ACTIONS:
                sendDeliveryActionsPush(event, step);
                break;
        }
    }

    private boolean isFullPayload() {
        return binding.fullPayloadCheckbox.isChecked();
    }

    private void sendDeliveryPush(String event, int step) {
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("progress_max", DELIVERY_TITLES.length);
            contentState.put("title", DELIVERY_TITLES[step]);
            contentState.put("body", DELIVERY_BODIES[step]);
            contentState.put("progress", step + 1);
            if (isFullPayload()) {
                attributes.put("segments", DELIVERY_SEGMENTS);
                attributes.put("start_icon", DEMO_ICON);
                attributes.put("end_icon", DEMO_ICON);
                attributes.put("tracker_icon", DEMO_ICON);
                attributes.put("large_icon", DEMO_LARGE_ICON_URL);
                attributes.put("colorized", true);
                attributes.put("dismiss_delay", DEMO_DISMISS_DELAY_MS);
                contentState.put("subtext", DELIVERY_SUBTEXTS[step]);
                contentState.put("color", DELIVERY_COLOR);
            }
        } catch (JSONException ignored) { }
        fire(baseBundle(DELIVERY_ID, event, TYPE_PROGRESS, attributes, contentState));
    }

    private void sendTimerPush(String event, int step) {
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            contentState.put("title", TIMER_TITLES[step]);
            contentState.put("body", TIMER_BODIES[step]);
            if (!event.equals(EVENT_END)) {
                long countdownUntil = System.currentTimeMillis() + 5 * 1000;
                contentState.put("countdown_until", countdownUntil);
            }
            if (isFullPayload()) {
                attributes.put("large_icon", DEMO_LARGE_ICON_URL);
                attributes.put("colorized", true);
                attributes.put("dismiss_delay", DEMO_DISMISS_DELAY_MS);
                contentState.put("subtext", TIMER_SUBTEXTS[step]);
                contentState.put("color", TIMER_COLORS[step]);
                contentState.put("actions", TIMER_ACTIONS);
            }
        } catch (JSONException ignored) { }
        fire(baseBundle(TIMER_ID, event, TYPE_COUNTDOWN, attributes, contentState));
    }

    private void sendSportsPush(String event, int step) {
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            contentState.put("title", SPORTS_TITLES[step]);
            contentState.put("body", SPORTS_BODIES[step]);
            if (isFullPayload()) {
                attributes.put("large_icon", DEMO_LARGE_ICON_URL);
                attributes.put("colorized", true);
                attributes.put("dismiss_delay", DEMO_DISMISS_DELAY_MS);
                contentState.put("subtext", SPORTS_SUBTEXTS[step]);
                contentState.put("color", SPORTS_COLOR);
                contentState.put("actions", SPORTS_ACTIONS);
            }
        } catch (JSONException ignored) { }
        fire(baseBundle(SPORTS_ID, event, TYPE_TEXT, attributes, contentState));
    }

    private void sendDeliveryActionsPush(String event, int step) {
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("progress_max", ACTIONS_TITLES.length);
            contentState.put("title", ACTIONS_TITLES[step]);
            contentState.put("body", ACTIONS_BODIES[step]);
            contentState.put("progress", step + 1);
            if (isFullPayload()) {
                attributes.put("segments", ACTIONS_SEGMENTS);
                attributes.put("start_icon", DEMO_ICON);
                attributes.put("end_icon", DEMO_ICON);
                attributes.put("large_icon", DEMO_LARGE_ICON_URL);
                attributes.put("colorized", true);
                attributes.put("dismiss_delay", DEMO_DISMISS_DELAY_MS);
                int minutesRemaining = Math.max(1, (ACTIONS_TITLES.length - step) * 3);
                contentState.put("subtext", "ETA: " + minutesRemaining + " min");
                contentState.put("color", ACTIONS_COLOR);
                contentState.put("actions", ACTIONS_BUTTONS);
            }
        } catch (JSONException ignored) { }
        Bundle bundle = baseBundle(ACTIONS_ID, event, TYPE_PROGRESS, attributes, contentState);
        if (isFullPayload()) {
            bundle.putString("link", "sample://order/456/details");
        }
        fire(bundle);
    }

    private Bundle baseBundle(String activityId, String event, String activityType,
                              JSONObject attributes, JSONObject contentState) {
        Bundle bundle = new Bundle();
        bundle.putString("CIO-Delivery-ID", UUID.randomUUID().toString());
        bundle.putString("CIO-Delivery-Token", DEMO_DELIVERY_TOKEN);
        bundle.putString("activity_id", activityId);
        bundle.putString("event", event);
        bundle.putString("activity_type", activityType);
        bundle.putString("attributes", attributes.toString());
        bundle.putString("content_state", contentState.toString());
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
        NotificationType type = getSelectedType();
        String label;
        switch (type) {
            case TIMER: label = TIMER_TITLES[currentStep]; break;
            case SPORTS: label = SPORTS_TITLES[currentStep]; break;
            case DELIVERY_ACTIONS: label = ACTIONS_TITLES[currentStep]; break;
            default: label = DELIVERY_TITLES[currentStep]; break;
        }
        binding.statusTextView.setText(getString(R.string.live_notification_status_format, label, currentStep + 1));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoHandler.removeCallbacksAndMessages(null);
    }
}

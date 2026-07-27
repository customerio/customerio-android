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
 * synthetic push messages through the SDK's real push handling path, and also
 * exercises the local-start API and a backend-campaign trigger.
 */
public class LiveNotificationDemoActivity extends BaseActivity<ActivityLiveNotificationDemoBinding> {

    private static final String DEMO_DELIVERY_TOKEN = "demo-token-live";
    private static final long AUTO_STEP_DELAY_MS = 5000;

    private static final String EVENT_START = "start";
    private static final String EVENT_UPDATE = "update";
    private static final String EVENT_END = "end";

    // activity_type values match the iOS Live Notification identifiers per the cross-platform spec.
    private static final String ACTIVITY_TYPE_SEGMENTS = "io.customer.livenotifications.segments";
    private static final String ACTIVITY_TYPE_COUNTDOWN_TIMER = "io.customer.livenotifications.countdowntimer";
    private static final String ACTIVITY_TYPE_UNKNOWN = "io.customer.livenotifications.bogus";
    // Custom (app-rendered) types — rendered by LiveNotificationCallback, not an SDK template.
    private static final String ACTIVITY_TYPE_RIDESHARE = LiveNotificationCallback.ACTIVITY_TYPE_RIDESHARE;
    private static final String ACTIVITY_TYPE_WORKOUT = LiveNotificationCallback.ACTIVITY_TYPE_WORKOUT;

    private enum TemplateChoice {
        SEGMENTS, COUNTDOWN_TIMER,
        CUSTOM_RIDESHARE, CUSTOM_WORKOUT
    }

    // Event the backend campaign listens for; its `activity_type` property selects the template.
    private static final String CAMPAIGN_EVENT = "trigger_live";
    // Dropdown order must match the labels built in setupCampaignDropdown below.
    private static final String[] CAMPAIGN_ACTIVITY_TYPES = {
            ACTIVITY_TYPE_SEGMENTS,
            ACTIVITY_TYPE_COUNTDOWN_TIMER
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
        if (checkedId == R.id.radio_countdown_timer) return TemplateChoice.COUNTDOWN_TIMER;
        if (checkedId == R.id.radio_custom_rideshare) return TemplateChoice.CUSTOM_RIDESHARE;
        if (checkedId == R.id.radio_custom_workout) return TemplateChoice.CUSTOM_WORKOUT;
        return TemplateChoice.SEGMENTS;
    }

    private int getStepCount() {
        switch (getSelectedTemplate()) {
            // Segments: ordered → preparing → out-for-delivery → delivered
            case SEGMENTS: return 4;
            // Countdown: 5 min out → 30s out → finished (push-driven, no endTime)
            case COUNTDOWN_TIMER: return 3;
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
            case SEGMENTS: sendSegments(event, step); break;
            case COUNTDOWN_TIMER: sendCountdownTimer(event, step); break;
            case CUSTOM_RIDESHARE: sendCustomRideshare(event, step); break;
            case CUSTOM_WORKOUT: sendCustomWorkout(event, step); break;
        }
    }

    private void sendSegments(String event, int step) {
        // A food-delivery order flow (bold status headline + supporting line), the
        // canonical Android 16 ProgressStyle "Live Update" use case.
        String[] statuses = {
                "Thanks for your order",
                "Your order is being prepared",
                "Out for delivery",
                "Delivered"
        };
        String[] substatuses = {
                "We've received your order and we're on it",
                "The kitchen is cooking it up",
                "Your rider is on the way",
                "Enjoy your meal!"
        };
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("header", "Order #4021");

            contentState.put("status", statuses[step]);
            contentState.put("substatus", substatuses[step]);
            contentState.put("segmentsTotal", statuses.length);
            contentState.put("segmentsComplete", step + 1);
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-segments", event, ACTIVITY_TYPE_SEGMENTS, attributes, contentState));
    }

    private void sendCountdownTimer(String event, int step) {
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("header", "Limited time");

            // endTime is epoch SECONDS on the wire, not millis.
            long nowSeconds = System.currentTimeMillis() / 1000;
            if (step == 2) {
                // Finished state: a "done" title with no endTime, so the template drops the timer.
                contentState.put("title", "Sale is live!");
                contentState.put("statusMessage", "Shop now");
            } else {
                long[] offsets = {5 * 60L, 30L};
                contentState.put("title", "Flash sale ends in");
                contentState.put("statusMessage", "Don't miss out");
                contentState.put("endTime", nowSeconds + offsets[step]);
            }
        } catch (JSONException ignored) { }
        fire(buildBundle("demo-countdown-timer", event, ACTIVITY_TYPE_COUNTDOWN_TIMER, attributes, contentState));
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
        int[] progress = {15, 40, 80, 100};
        JSONObject attributes = new JSONObject();
        JSONObject contentState = new JSONObject();
        try {
            attributes.put("driverName", "Alex");
            attributes.put("vehicle", "Toyota Prius");
            attributes.put("plate", "7XYZ123");
            attributes.put("rating", "4.9");

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

    /** Demonstrates the typed local-start API: {@code startLiveNotification(LiveNotificationData)}. */
    private void startViaApi() {
        ModuleMessagingPushFCM module = customerIORepository.getMessagingPushModule();
        if (module == null) return;
        LiveNotificationData.Segments data = new LiveNotificationData.Segments(
                /* header */ "Order #API-1001",
                /* status */ "Out for delivery (started via API)",
                /* substatus */ "Driver: Sara",
                /* segmentsTotal */ 4,
                /* segmentsComplete */ 3,
                /* trailingText */ "5 min"
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
        ModuleMessagingPushFCM module = customerIORepository.getMessagingPushModule();
        if (module == null || lastApiActivityId == null) return;
        LiveNotificationData.Segments data = new LiveNotificationData.Segments(
                /* header */ "Order #API-1001",
                /* status */ "Arriving now (updated via API)",
                /* substatus */ "Driver: Sara",
                /* segmentsTotal */ 4,
                /* segmentsComplete */ 4,
                /* trailingText */ "Now"
        );
        module.updateLiveNotification(lastApiActivityId, data);
        binding.statusTextView.setText(getString(R.string.live_notification_status_format, "API:" + lastApiActivityId, 2));
    }

    /** Demonstrates the local-end API: {@code endLiveNotification(activityId)}. */
    private void endViaApi() {
        ModuleMessagingPushFCM module = customerIORepository.getMessagingPushModule();
        if (module == null || lastApiActivityId == null) return;
        module.endLiveNotification(lastApiActivityId);
        binding.statusTextView.setText(R.string.live_notification_status_ended);
        lastApiActivityId = null;
    }

    // --- Campaign trigger ---

    private void setupCampaignDropdown() {
        String[] labels = {
                getString(R.string.live_notification_type_segments),
                getString(R.string.live_notification_type_countdown_timer)
        };
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        binding.campaignTemplateDropdown.setAdapter(adapter);
        binding.campaignTemplateDropdown.setText(labels[selectedCampaignIndex], false);
        binding.campaignTemplateDropdown.setOnItemClickListener(
                (parent, view, position, id) -> selectedCampaignIndex = position);
    }

    /**
     * Tracks the {@code trigger_live} event so a backend campaign pushes the real
     * start/update/end lifecycle through the live-notification path.
     */
    private void triggerCampaign() {
        String activityType = CAMPAIGN_ACTIVITY_TYPES[selectedCampaignIndex];
        Map<String, String> properties = new HashMap<>();
        properties.put("activity_type", activityType);
        // Unique per trigger; the campaign builds a fresh cioInstanceId from it via Liquid.
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
        // The SDK routes to the live-notification handler by the presence of `cioInstanceId`.
        bundle.putString("cioInstanceId", activityId);
        bundle.putString("event", event);
        bundle.putString("notification_type", activityType);
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
            // Nested objects ride along as JSON strings, matching how FCM delivers them.
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

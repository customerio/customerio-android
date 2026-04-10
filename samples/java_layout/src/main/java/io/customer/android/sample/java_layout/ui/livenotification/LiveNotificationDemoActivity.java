package io.customer.android.sample.java_layout.ui.livenotification;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.messaging.RemoteMessage;

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
 */
public class LiveNotificationDemoActivity extends BaseActivity<ActivityLiveNotificationDemoBinding> {

    private static final String DEMO_DELIVERY_TOKEN = "demo-token-live";
    private static final long AUTO_STEP_DELAY_MS = 2000;

    // Shared demo assets
    private static final String DEMO_ICON = "ic_notification";
    private static final String DEMO_LARGE_ICON_URL = "https://images-wixmp-ed30a86b8c4ca887773594c2.wixmp.com/f/a0a7586b-3d38-4293-9d13-75e10782ff57/dgy0t9h-e6de6201-962c-47d5-b227-a948394fdd89.png?token=eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1cm46YXBwOjdlMGQxODg5ODIyNjQzNzNhNWYwZDQxNWVhMGQyNmUwIiwiaXNzIjoidXJuOmFwcDo3ZTBkMTg4OTgyMjY0MzczYTVmMGQ0MTVlYTBkMjZlMCIsIm9iaiI6W1t7InBhdGgiOiIvZi9hMGE3NTg2Yi0zZDM4LTQyOTMtOWQxMy03NWUxMDc4MmZmNTcvZGd5MHQ5aC1lNmRlNjIwMS05NjJjLTQ3ZDUtYjIyNy1hOTQ4Mzk0ZmRkODkucG5nIn1dXSwiYXVkIjpbInVybjpzZXJ2aWNlOmZpbGUuZG93bmxvYWQiXX0.XBVeXq1J6SOYsJOPsp1l7E-JdkHpdyM2xxtakzx9MaQ";

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

    // Parking timer config
    private static final String TIMER_ID = "demo-parking";
    private static final String[] TIMER_TITLES = {"Parking Session", "Parking Session", "Parking Expired"};
    private static final String[] TIMER_BODIES = {"Zone A - Spot 42", "Zone A - Spot 42", "Your session has ended"};
    private static final String[] TIMER_SUBTEXTS = {"Time remaining", "Expiring soon", "Expired"};
    private static final String[] TIMER_COLORS = {"#2E7D32", "#E65100", "#B71C1C"};

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
        sendPush("started", currentStep);
        updateStatusText();
    }

    private void update() {
        if (!isActive) return;
        currentStep = Math.min(currentStep + 1, getStepCount() - 1);
        updateButtonStates();
        sendPush("updated", currentStep);
        updateStatusText();
    }

    private void end() {
        if (!isActive) return;
        isActive = false;
        updateButtonStates();
        sendPush("ended", getStepCount() - 1);
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

    private void sendPush(String status, int step) {
        switch (getSelectedType()) {
            case DELIVERY:
                sendDeliveryPush(status, step);
                break;
            case TIMER:
                sendTimerPush(status, step);
                break;
            case SPORTS:
                sendSportsPush(status, step);
                break;
            case DELIVERY_ACTIONS:
                sendDeliveryActionsPush(status, step);
                break;
        }
    }

    private void sendDeliveryPush(String status, int step) {
        Bundle bundle = baseBundle(DELIVERY_ID, status, DELIVERY_TITLES[step], DELIVERY_BODIES[step]);
        bundle.putString("cio_live_notification_segments", DELIVERY_SEGMENTS);
        bundle.putString("cio_live_notification_progress", String.valueOf(step + 1));
        bundle.putString("cio_live_notification_progress_max", String.valueOf(DELIVERY_TITLES.length));
        bundle.putString("cio_live_notification_subtext", DELIVERY_SUBTEXTS[step]);
        bundle.putString("cio_live_notification_color", DELIVERY_COLOR);
        bundle.putString("cio_live_notification_colorized", "true");
        bundle.putString("cio_live_notification_start_icon", DEMO_ICON);
        bundle.putString("cio_live_notification_end_icon", DEMO_ICON);
        bundle.putString("cio_live_notification_tracker_icon", DEMO_ICON);
        bundle.putString("cio_live_notification_large_icon", DEMO_LARGE_ICON_URL);
        bundle.putString("cio_live_notification_dismiss_delay", "5000");
        fire(bundle);
    }

    private void sendTimerPush(String status, int step) {
        Bundle bundle = baseBundle(TIMER_ID, status, TIMER_TITLES[step], TIMER_BODIES[step]);
        bundle.putString("cio_live_notification_subtext", TIMER_SUBTEXTS[step]);
        bundle.putString("cio_live_notification_color", TIMER_COLORS[step]);
        bundle.putString("cio_live_notification_colorized", "true");
        bundle.putString("cio_live_notification_type", "countdown");
        bundle.putString("cio_live_notification_large_icon", DEMO_LARGE_ICON_URL);
        bundle.putString("cio_live_notification_dismiss_delay", "5000");

        // Countdown: 5 seconds from now (shortened for demo)
        if (!status.equals("ended")) {
            long countdownUntil = System.currentTimeMillis() + 5 * 1000;
            bundle.putString("cio_live_notification_countdown_until", String.valueOf(countdownUntil));
        }

        bundle.putString("cio_live_notification_actions",
                "[{\"label\":\"Extend Parking\",\"link\":\"sample://parking/extend\"}]");
        fire(bundle);
    }

    private void sendSportsPush(String status, int step) {
        Bundle bundle = baseBundle(SPORTS_ID, status, SPORTS_TITLES[step], SPORTS_BODIES[step]);
        bundle.putString("cio_live_notification_subtext", SPORTS_SUBTEXTS[step]);
        bundle.putString("cio_live_notification_color", SPORTS_COLOR);
        bundle.putString("cio_live_notification_colorized", "true");
        bundle.putString("cio_live_notification_type", "text");
        bundle.putString("cio_live_notification_large_icon", DEMO_LARGE_ICON_URL);
        bundle.putString("cio_live_notification_dismiss_delay", "5000");
        bundle.putString("cio_live_notification_actions",
                "[{\"label\":\"Open Scorecard\",\"link\":\"sample://sports/game/123\"}]");
        fire(bundle);
    }

    private void sendDeliveryActionsPush(String status, int step) {
        Bundle bundle = baseBundle(ACTIONS_ID, status, ACTIONS_TITLES[step], ACTIONS_BODIES[step]);
        bundle.putString("cio_live_notification_segments", ACTIONS_SEGMENTS);
        bundle.putString("cio_live_notification_progress", String.valueOf(step + 1));
        bundle.putString("cio_live_notification_progress_max", String.valueOf(ACTIONS_TITLES.length));
        bundle.putString("cio_live_notification_subtext", "ETA: " + Math.max(1, (ACTIONS_TITLES.length - step) * 3) + " min");
        bundle.putString("cio_live_notification_color", ACTIONS_COLOR);
        bundle.putString("cio_live_notification_colorized", "true");
        bundle.putString("cio_live_notification_start_icon", DEMO_ICON);
        bundle.putString("cio_live_notification_end_icon", DEMO_ICON);
        bundle.putString("cio_live_notification_large_icon", DEMO_LARGE_ICON_URL);
        bundle.putString("cio_live_notification_dismiss_delay", "5000");
        bundle.putString("link", "sample://order/456/details");
        bundle.putString("cio_live_notification_actions",
                "[{\"label\":\"View Order\",\"link\":\"sample://order/456\"},{\"label\":\"Get Directions\",\"link\":\"sample://directions\"}]");
        fire(bundle);
    }

    private Bundle baseBundle(String liveId, String status, String title, String body) {
        Bundle bundle = new Bundle();
        bundle.putString("CIO-Delivery-ID", UUID.randomUUID().toString());
        bundle.putString("CIO-Delivery-Token", DEMO_DELIVERY_TOKEN);
        bundle.putString("title", title);
        bundle.putString("body", body);
        bundle.putString("cio_live_notification_id", liveId);
        bundle.putString("cio_live_notification_status", status);
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

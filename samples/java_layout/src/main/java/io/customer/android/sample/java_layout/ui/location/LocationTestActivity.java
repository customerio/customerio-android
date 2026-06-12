package io.customer.android.sample.java_layout.ui.location;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.ActivityLocationTestBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.location.ModuleLocation;
import io.customer.sdk.CustomerIO;

public class LocationTestActivity extends BaseActivity<ActivityLocationTestBinding> {

    private static final double[][] PRESET_COORDS = {
            {40.7128, -74.0060},   // New York
            {51.5074, -0.1278},    // London
            {35.6762, 139.6503},   // Tokyo
            {-33.8688, 151.2093},  // Sydney
            {-23.5505, -46.6333},  // Sao Paulo
            {0.0, 0.0}            // 0, 0
    };
    private static final String[] PRESET_NAMES = {
            "New York", "London", "Tokyo", "Sydney", "Sao Paulo", "0, 0"
    };

    private enum BgPermissionState {
        NOT_DETERMINED,    // Fine location not yet granted, never permanently denied.
        FOREGROUND_ONLY,   // Fine granted; background (API 29+) not yet granted.
        BACKGROUND_GRANTED, // Both granted (or pre-Q where background is implicit).
        DENIED              // Fine denied with "don't ask again".
    }

    // Why the user tapped the fine-location permission prompt — read in the launcher
    // callback to dispatch the right follow-up. Mutually exclusive; NONE between launches.
    private enum PendingPermissionPurpose { NONE, CURRENT_LOCATION, SDK_LOCATION, BACKGROUND_UPGRADE }

    private LocationManager locationManager;
    private LocationListener locationListener;
    private PendingPermissionPurpose pendingPurpose = PendingPermissionPurpose.NONE;
    private boolean wasFineDeniedPermanently = false;
    // Whether requestLocationUpdate() has been called for the current BACKGROUND_GRANTED
    // session. Prevents redundant SDK fetches when onResume fires after a launcher callback
    // already triggered one; cleared if permission is revoked so we re-arm for the next grant.
    private boolean bgGrantHandled = false;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                boolean fineGranted = Boolean.TRUE.equals(permissions.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarseGranted = Boolean.TRUE.equals(permissions.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                boolean anyGranted = fineGranted || coarseGranted;
                PendingPermissionPurpose purpose = pendingPurpose;
                pendingPurpose = PendingPermissionPurpose.NONE;

                if (anyGranted) {
                    wasFineDeniedPermanently = false;
                    switch (purpose) {
                        case CURRENT_LOCATION:
                            fetchDeviceLocation();
                            break;
                        case SDK_LOCATION:
                            performSdkLocationRequest();
                            break;
                        case BACKGROUND_UPGRADE:
                            if (hasBackgroundLocation()) {
                                // Pre-Q: fine granted is implicitly background. No second permission
                                // to request — trigger the post-grant SDK fetch directly (same as the
                                // backgroundLocationLauncher path).
                                triggerPostGrantSdkFetch();
                            } else {
                                // API 29+: foreground just granted; rationale → background prompt.
                                showBackgroundLocationRationale();
                            }
                            break;
                        case NONE:
                            break;
                    }
                } else {
                    // shouldShowRequestPermissionRationale returns false after "don't ask again"
                    // (and on first-ever request, but here we've already requested at least once).
                    wasFineDeniedPermanently = !ActivityCompat.shouldShowRequestPermissionRationale(
                            this, Manifest.permission.ACCESS_FINE_LOCATION);
                    if (purpose == PendingPermissionPurpose.CURRENT_LOCATION
                            || purpose == PendingPermissionPurpose.SDK_LOCATION) {
                        showPermissionDeniedAlert();
                    }
                }
                refreshGrantBackgroundLocationUI();
            });

    // Launcher behavior varies by API:
    // - API 29: shows the runtime dialog with "Allow all the time".
    // - API 30+: OS may route to Settings (recent Pixel/Samsung) or silently no-op (others).
    //   Don't trust the `granted` flag — re-check actual permission state.
    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (hasBackgroundLocation()) {
                    // Granted — kick off a fetch so geofences register now. The SDK's auto-fetch
                    // lifecycle hook fires once per process and has already run, so an explicit
                    // request is needed after a runtime grant.
                    triggerPostGrantSdkFetch();
                }
                // Not granted: respect the user's choice (API 29 dialog declined, API 30+ silent
                // deny, or backed out of Settings). The next tap re-shows the rationale dialog,
                // which has an explicit "Open Settings" action for recovery.
                refreshGrantBackgroundLocationUI();
            });

    @Override
    protected ActivityLocationTestBinding inflateViewBinding() {
        return ActivityLocationTestBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void setupContent() {
        binding.topAppBar.setNavigationOnClickListener(v -> onBackPressed());

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        CustomerIO.instance().screen(getString(R.string.location_test_title));

        setupPresetButtons();
        setupSdkLocationButtons();
        setupDeviceLocationButton();
        setupManualEntrySection();
        setupBackgroundPermissionButton();

        // SDK lifecycle handles the initial-launch fetch if permission was already granted
        // at process start. Seed so onResume only fires on transitions occurring while this
        // activity is alive.
        bgGrantHandled = (computeBgPermissionState() == BgPermissionState.BACKGROUND_GRANTED);
    }

    private void triggerPostGrantSdkFetch() {
        bgGrantHandled = true;
        ModuleLocation.instance().getLocationServices().requestLocationUpdate();
    }

    private void setupBackgroundPermissionButton() {
        binding.grantBackgroundLocation.setOnClickListener(v -> handleGrantBackgroundLocationTap());
        refreshGrantBackgroundLocationUI();
    }

    private BgPermissionState computeBgPermissionState() {
        if (hasBackgroundLocation()) return BgPermissionState.BACKGROUND_GRANTED;
        if (hasFineLocation()) return BgPermissionState.FOREGROUND_ONLY;
        if (wasFineDeniedPermanently) return BgPermissionState.DENIED;
        return BgPermissionState.NOT_DETERMINED;
    }

    private void refreshGrantBackgroundLocationUI() {
        BgPermissionState state = computeBgPermissionState();
        switch (state) {
            case NOT_DETERMINED:
                binding.grantBackgroundLocation.setText(R.string.bg_perm_button_not_determined);
                binding.grantBackgroundLocation.setEnabled(true);
                binding.grantBackgroundLocation.setAlpha(1.0f);
                binding.grantBackgroundStatusLabel.setText(R.string.bg_perm_status_not_determined);
                break;
            case FOREGROUND_ONLY:
                binding.grantBackgroundLocation.setText(R.string.bg_perm_button_foreground_only);
                binding.grantBackgroundLocation.setEnabled(true);
                binding.grantBackgroundLocation.setAlpha(1.0f);
                binding.grantBackgroundStatusLabel.setText(R.string.bg_perm_status_foreground_only);
                break;
            case BACKGROUND_GRANTED:
                binding.grantBackgroundLocation.setText(R.string.bg_perm_button_background_granted);
                binding.grantBackgroundLocation.setEnabled(false);
                binding.grantBackgroundLocation.setAlpha(0.6f);
                binding.grantBackgroundStatusLabel.setText(R.string.bg_perm_status_background_granted);
                break;
            case DENIED:
                binding.grantBackgroundLocation.setText(R.string.bg_perm_button_denied);
                binding.grantBackgroundLocation.setEnabled(true);
                binding.grantBackgroundLocation.setAlpha(1.0f);
                binding.grantBackgroundStatusLabel.setText(R.string.bg_perm_status_denied);
                break;
        }
    }

    private void handleGrantBackgroundLocationTap() {
        switch (computeBgPermissionState()) {
            case NOT_DETERMINED:
                // Two-step: request fine first; on success the launcher callback prompts for background.
                pendingPurpose = PendingPermissionPurpose.BACKGROUND_UPGRADE;
                locationPermissionLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                });
                break;
            case FOREGROUND_ONLY:
                showBackgroundLocationRationale();
                break;
            case BACKGROUND_GRANTED:
                // No-op; button is disabled.
                break;
            case DENIED:
                openAppDetailsSettings();
                break;
        }
    }

    private void showBackgroundLocationRationale() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bg_perm_rationale_title)
                .setMessage(R.string.background_location_rationale_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.background_location_continue, (dialog, which) ->
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                .setNeutralButton(R.string.open_settings, (dialog, which) -> openAppDetailsSettings())
                .show();
    }

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        // Pre-Q has no separate background permission — background access is implicit
        // only when fine is granted, so gate the early return on hasFineLocation().
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasFineLocation();
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void openAppDetailsSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void setupPresetButtons() {
        int[] buttonIds = {
                R.id.preset_new_york, R.id.preset_london, R.id.preset_tokyo,
                R.id.preset_sydney, R.id.preset_sao_paulo, R.id.preset_zero
        };
        for (int i = 0; i < buttonIds.length; i++) {
            final int index = i;
            findViewById(buttonIds[i]).setOnClickListener(v ->
                    setLocation(PRESET_COORDS[index][0], PRESET_COORDS[index][1], PRESET_NAMES[index])
            );
        }
    }

    private void setupSdkLocationButtons() {
        binding.requestSdkLocationOnce.setOnClickListener(v -> requestSdkLocationOnce());
    }

    private void setupDeviceLocationButton() {
        binding.useCurrentLocation.setOnClickListener(v -> requestCurrentLocation());
    }

    private void setupManualEntrySection() {
        binding.setManualLocation.setOnClickListener(v -> setManualLocation());
    }

    // --- Location Actions ---

    private void setLocation(double latitude, double longitude, String sourceName) {
        ModuleLocation.instance().getLocationServices().setLastKnownLocation(latitude, longitude);
        String sourceText = sourceName != null ? " (" + sourceName + ")" : "";
        binding.lastSetLocationLabel.setText(
                getString(R.string.last_set_format, latitude, longitude, sourceText)
        );
        showSnackbar(getString(R.string.location_set_success, sourceText));
    }

    private void setManualLocation() {
        String latText = binding.latitudeInput.getText() != null
                ? binding.latitudeInput.getText().toString().trim() : "";
        String lonText = binding.longitudeInput.getText() != null
                ? binding.longitudeInput.getText().toString().trim() : "";

        if (latText.isEmpty() || lonText.isEmpty()) {
            showSnackbar(getString(R.string.enter_valid_coordinates));
            return;
        }

        try {
            double latitude = Double.parseDouble(latText);
            double longitude = Double.parseDouble(lonText);
            setLocation(latitude, longitude, "Manual");
        } catch (NumberFormatException e) {
            showSnackbar(getString(R.string.enter_valid_coordinates));
        }
    }

    private void requestSdkLocationOnce() {
        if (isLocationPermissionGranted()) {
            performSdkLocationRequest();
        } else {
            pendingPurpose = PendingPermissionPurpose.SDK_LOCATION;
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void performSdkLocationRequest() {
        binding.lastSetLocationLabel.setText(R.string.requesting_location_sdk);
        ModuleLocation.instance().getLocationServices().requestLocationUpdate();
        showSnackbar(getString(R.string.sdk_requested_location));
    }

    private void requestCurrentLocation() {
        if (isLocationPermissionGranted()) {
            fetchDeviceLocation();
        } else {
            pendingPurpose = PendingPermissionPurpose.CURRENT_LOCATION;
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    @SuppressWarnings("MissingPermission")
    private void fetchDeviceLocation() {
        binding.useCurrentLocation.setEnabled(false);
        binding.useCurrentLocation.setText(R.string.fetching_location);

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                locationManager.removeUpdates(this);
                setLocation(location.getLatitude(), location.getLongitude(), "Device");
                binding.useCurrentLocation.setEnabled(true);
                binding.useCurrentLocation.setText(R.string.use_current_location);
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                showSnackbar(getString(R.string.location_not_available));
                binding.useCurrentLocation.setEnabled(true);
                binding.useCurrentLocation.setText(R.string.use_current_location);
            }
        };

        String provider = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER
                : LocationManager.NETWORK_PROVIDER;

        locationManager.requestSingleUpdate(provider, locationListener, null);
    }

    // --- Permission Helpers ---

    private boolean isLocationPermissionGranted() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionDeniedAlert() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.location_permission_required)
                .setMessage(R.string.location_permission_failure)
                .setNeutralButton(R.string.open_settings, (dialog, which) -> openAppDetailsSettings())
                .show();
    }

    private void showSnackbar(String message) {
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Permission may have been toggled in Settings while we were in the background.
        // If we previously flagged "permanently denied" but the system now offers rationale,
        // the user lifted don't-ask-again — treat the denial as recoverable.
        if (wasFineDeniedPermanently && !hasFineLocation()
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            wasFineDeniedPermanently = false;
        }
        if (computeBgPermissionState() == BgPermissionState.BACKGROUND_GRANTED) {
            if (!bgGrantHandled) {
                // Settings round-trip granted background while we were away — fire the same
                // post-grant SDK fetch the launcher callbacks do, so geofences register without
                // waiting for the next process start.
                triggerPostGrantSdkFetch();
            }
        } else {
            bgGrantHandled = false; // permission revoked — re-arm for the next grant cycle
        }
        refreshGrantBackgroundLocationUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationListener != null && locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }
}

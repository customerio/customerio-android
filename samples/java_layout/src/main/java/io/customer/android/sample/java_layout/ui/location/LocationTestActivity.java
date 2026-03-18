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
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.ActivityLocationTestBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.location.ModuleLocation;
import io.customer.location.geofence.GeofenceRegion;
import io.customer.location.geofence.GeofenceServices;
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

    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean userRequestedCurrentLocation = false;
    private boolean userRequestedSdkLocation = false;
    private boolean userRequestedGeofencing = false;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                boolean fineGranted = Boolean.TRUE.equals(permissions.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarseGranted = Boolean.TRUE.equals(permissions.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (fineGranted || coarseGranted) {
                    if (userRequestedCurrentLocation) {
                        userRequestedCurrentLocation = false;
                        fetchDeviceLocation();
                    } else if (userRequestedSdkLocation) {
                        userRequestedSdkLocation = false;
                        performSdkLocationRequest();
                    } else if (userRequestedGeofencing) {
                        userRequestedGeofencing = false;
                        addSampleGeofences();
                    }
                } else {
                    showPermissionDeniedAlert();
                }
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
        setupGeofencingSection();
        updateGeofenceStatus();
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

    private void setupGeofencingSection() {
        binding.addSampleGeofences.setOnClickListener(v -> requestGeofencing());
        binding.removeAllGeofences.setOnClickListener(v -> removeAllGeofences());
    }

    // --- Location Actions ---

    private void setLocation(double latitude, double longitude, String sourceName) {
        ModuleLocation.instance().getLocationServices().setLastKnownLocation(latitude, longitude);
/*

        // Create unique geofence ID based on source or coordinates
        String geofenceId = sourceName != null
            ? "geofence_" + sourceName.toLowerCase().replace(" ", "_")
            : "geofence_" + latitude + "_" + longitude;

        ModuleLocation.instance().getLocationServices().getGeofenceServices().addGeofences(
                List.of(new GeofenceRegion(
                        geofenceId,
                        latitude,
                        longitude,
                        100.0, // 100 meter radius
                        sourceName != null ? sourceName + " Geofence" : "Manual Geofence",
                        null,
                        1000L // 1 second dwell time for testing
                ))
        );
*/
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
            userRequestedSdkLocation = true;
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
            userRequestedCurrentLocation = true;
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
                .setNeutralButton(R.string.open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .show();
    }

    private void showSnackbar(String message) {
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    // --- Geofencing Actions ---

    private void requestGeofencing() {
        if (isLocationPermissionGranted()) {
            addSampleGeofences();
        } else {
            userRequestedGeofencing = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+, also request background location for geofencing
                locationPermissionLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                });
            } else {
                locationPermissionLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                });
            }
        }
    }

    class PresetLocation {
        String name;
        String type;
        double latitude;
        double longitude;

        PresetLocation(String name, String type, double latitude, double longitude) {
            this.name = name;
            this.type = type;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private void addSampleGeofences() {
        GeofenceServices geofenceServices = ModuleLocation.instance()
                .getLocationServices()
                .getGeofenceServices();

        List<GeofenceRegion> regions = new ArrayList<>();

        PresetLocation[] presets = {
                new PresetLocation("Anarkali Bazaar", "Market", 31.569920255631676, 74.3124273864531),
                new PresetLocation("Dolmen Mall", "Mall", 31.46794689582185, 74.43590552575039),
                new PresetLocation("JW Marriott Hotel Riyadh", "Hotel", 25.06613635596822, 46.6764801938949),
                new PresetLocation("Kareem Block Market", "Market", 31.504004050496576, 74.28084262389915),
                new PresetLocation("Liberty Market", "Market", 31.510354633356954, 74.3437341288528),
                new PresetLocation("New York", "City", 40.7128, -74.0060),
                new PresetLocation("San Francisco", "City", 37.7749, -122.4194),
                new PresetLocation("Thokar", "Station", 31.49118471585734, 74.23891870917167)
        };
        // Add geofences for the preset locations
        for (PresetLocation loc : presets) {
            double distance;
            if (loc.name.toLowerCase().startsWith("jw")) {
                distance = 5000;
            } else {
                distance = 500;
            }
            regions.add(new GeofenceRegion(
                    "geofence_geofence_" + loc.name.replaceAll(" ", "_").toLowerCase(),
                    loc.latitude,
                    loc.longitude,
                    distance,
                    loc.name,
                    new HashMap<>() {
                        {
                            put("type", loc.type);
                        }
                    },
                    60 * 1000L // 1 minute dwell time
            ));
        }

        geofenceServices.addGeofences(regions);
        showSnackbar(getString(R.string.geofences_added, regions.size()));
        updateGeofenceStatus();
    }

    private void removeAllGeofences() {
        GeofenceServices geofenceServices = ModuleLocation.instance()
                .getLocationServices()
                .getGeofenceServices();

        geofenceServices.removeAllGeofences();
        showSnackbar(getString(R.string.geofences_removed));
        updateGeofenceStatus();
    }

    private void updateGeofenceStatus() {
        try {
            GeofenceServices geofenceServices = ModuleLocation.instance()
                    .getLocationServices()
                    .getGeofenceServices();
            int activeCount = geofenceServices.getActiveGeofences().size();
            binding.geofenceStatus.setText(getString(R.string.active_geofences_format, activeCount));
        } catch (Exception e) {
            binding.geofenceStatus.setText(getString(R.string.active_geofences_format, 0));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationListener != null && locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGeofenceStatus();
    }
}

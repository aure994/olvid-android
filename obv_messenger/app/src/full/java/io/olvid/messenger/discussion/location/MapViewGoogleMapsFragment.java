/*
 *  Olvid for Android
 *  Copyright © 2019-2022 Olvid SAS
 *
 *  This file is part of Olvid for Android.
 *
 *  Olvid is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License, version 3,
 *  as published by the Free Software Foundation.
 *
 *  Olvid is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with Olvid.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.olvid.messenger.discussion.location;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.core.util.Pair;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.MutableLiveData;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.olvid.engine.Logger;
import io.olvid.messenger.R;
import io.olvid.messenger.customClasses.LocationIntegrationSelectorDialog;
import io.olvid.messenger.customClasses.SecureAlertDialogBuilder;
import io.olvid.messenger.google_services.GoogleServicesUtils;
import io.olvid.messenger.settings.SettingsActivity;

public class MapViewGoogleMapsFragment extends MapViewAbstractFragment implements OnMapReadyCallback {

    private static final float DEFAULT_ZOOM = 16;
    private static final int TRANSITION_DURATION_MS = 500;

    @Nullable private Runnable onMapReadyCallback = null;
    private FragmentActivity activity;

    private SupportMapFragment mapFragment;
    @Nullable private GoogleMap googleMap;

    // current camera center live data (set to null if camera is moving)
    private final MutableLiveData<LatLngWrapper> currentCameraCenterLiveData = new MutableLiveData<>();

    // store markers by message id
    private final HashMap<Long, Marker> markersByIdHashMap = new HashMap<>();
    private final HashMap<Long, Circle> circlesByIdHashMap = new HashMap<>();

    // store last centered marker to reset it's zIndex property
    private Marker currentlyCenteredMarker = null;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // get current activity
        this.activity = requireActivity();

        // prepare map fragment
        mapFragment = SupportMapFragment.newInstance();
        mapFragment.getMapAsync(this);

        // if google services are not available show a pop up and dismiss dialog fragment
        if (!GoogleServicesUtils.googleServicesAvailable(activity)) {
            new SecureAlertDialogBuilder(this.activity, R.style.CustomAlertDialog)
                    .setTitle(R.string.dialog_title_google_maps_services_unavailable)
                    .setMessage(R.string.dialog_message_google_maps_services_unavailable)
                    .setPositiveButton(R.string.button_label_choose_provider,
                            (dialog, which) -> new LocationIntegrationSelectorDialog(activity, (SettingsActivity.LocationIntegrationEnum integration) -> SettingsActivity.setLocationIntegration(integration.getString())).show())
                    .show();
            if (getParentFragment() != null && getParentFragment() instanceof DialogFragment) {
                ((DialogFragment) getParentFragment()).dismiss();
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_map_view_google_maps, container, false);

        this.getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.map_view_container, mapFragment)
                .commit();

        return rootView;
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        this.googleMap = googleMap;

        googleMap.getUiSettings().setCompassEnabled(true);
        repositionCompass();

        // setup listeners for map gestures
        googleMap.setOnCameraMoveStartedListener((reason) -> {
            currentCameraCenterLiveData.postValue(null);

            // if user move map consider marker as not centered anymore
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                setCurrentlyCenteredMarker(null);
            }
        });
        // when move finish get center marker down and and update cameraCenterLiveData
        googleMap.setOnCameraMoveCanceledListener(() -> currentCameraCenterLiveData.postValue(new LatLngWrapper(googleMap.getCameraPosition().target)));
        googleMap.setOnCameraIdleListener(() -> currentCameraCenterLiveData.postValue(new LatLngWrapper(googleMap.getCameraPosition().target)));

        // customize map
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);
        googleMap.setBuildingsEnabled(false);
        googleMap.setIndoorEnabled(false);
        googleMap.setTrafficEnabled(false);

        // setup markers listeners
        googleMap.setOnMarkerClickListener(marker -> {
            // center on all markers if clicking on same marker when we have multiple markers (to continue following marker if there is only one)
            if (marker.equals(currentlyCenteredMarker) && markersByIdHashMap.size() > 1) {
                centerOnMarkers(true, true);
            } else {
                centerOnMarker(marker, true);
            }
            return true;
        });

        if (onMapReadyCallback != null) {
            this.onMapReadyCallback.run();
        }
    }

    private void repositionCompass() {
        try {
            int twelveDp = (int) (12 * activity.getResources().getDisplayMetrics().density);
            View mapView = mapFragment.getView();
            if (mapView != null) {
                View compass = mapView.findViewWithTag("GoogleMapCompass");
                if (compass != null) {
                    compass.post(() -> {
                        try {
                            // create layoutParams, giving it our wanted width and height(important, by default the width is "match parent")
                            RelativeLayout.LayoutParams rlp = new RelativeLayout.LayoutParams(compass.getHeight(), compass.getHeight());
                            // position on top right
                            rlp.addRule(RelativeLayout.ALIGN_PARENT_LEFT, 0);
                            rlp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                            rlp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                            rlp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 0);
                            //give compass margin
                            rlp.setMargins(0, twelveDp * 2, twelveDp, 0);
                            compass.setLayoutParams(rlp);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    });
                }
            }
        } catch (Exception ex) {
            Logger.w("Unable to reposition gmaps compass");
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public boolean setEnableCurrentLocation(boolean enabled) {
        // check map is ready
        if (googleMap == null) {
            Logger.i("GoogleMaps.enableCurrentLocation: map not initialized");
            return false;
        }

        // check permission and location is enabled (do not ask, father is supposed to do)
        if (!AbstractLocationDialogFragment.isLocationPermissionGranted(activity)) {
            return false;
        }
        if (!AbstractLocationDialogFragment.isLocationEnabled()) {
            return false;
        }

        // enable my location on google maps
        googleMap.setMyLocationEnabled(enabled);
        return enabled;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void centerOnCurrentLocation(boolean animate) {
        if (googleMap == null || !setEnableCurrentLocation(true)) {
            return;
        }

        // we concurrently request lastLocation and current location and we only keep the first one
        FusedLocationProviderClient fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this.activity);
        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
        CancellationToken cancellationToken = cancellationTokenSource.getToken();
        fusedLocationProviderClient.getLastLocation().addOnSuccessListener((result) -> {
            if (!cancellationToken.isCancellationRequested() && result != null) {
                centerOnLocation(result, animate);
                cancellationTokenSource.cancel();
            }
        });
        fusedLocationProviderClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.getToken()).addOnSuccessListener((result) -> {
            if (!cancellationToken.isCancellationRequested() && result != null) {
                centerOnLocation(result, animate);
                cancellationTokenSource.cancel();
            }
        });
    }

    private void centerOnLocation(@NonNull Location location, boolean animate) {
        if (googleMap != null) {
            CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngZoom(
                    new LatLng(location.getLatitude(), location.getLongitude()),
                    Float.max(DEFAULT_ZOOM, googleMap.getCameraPosition().zoom));
            if (animate) {
                googleMap.animateCamera(cameraUpdate, TRANSITION_DURATION_MS, null);
            }
            else {
                googleMap.moveCamera(cameraUpdate);
            }
        }
    }

    @Override
    public @NonNull MutableLiveData<LatLngWrapper> getCurrentCameraCenterLiveData() {
        return currentCameraCenterLiveData;
    }

    @Override
    public double getCameraZoom() {
        if (googleMap == null) {
            return 0;
        }
        return googleMap.getCameraPosition().zoom;
    }

    @Override
    public void launchMapSnapshot(@NonNull Consumer<Bitmap> onSnapshotReadyCallback) {
        if (googleMap == null) {
            Logger.i("GoogleMaps: launchMapSnapshot: map not ready when taking snapshot");
            onSnapshotReadyCallback.accept(null);
            return;
        }

        // hide compass
        googleMap.getUiSettings().setCompassEnabled(false);

        // need to delay snapshot cause disabling currentLocation cause a map refresh and sometimes places are not shown on snapshot
        new Handler(Looper.getMainLooper()).postDelayed(() -> googleMap.snapshot(onSnapshotReadyCallback::accept), 500);
    }

    @Override
    public void setGestureEnabled(boolean enabled) {
        if (googleMap == null) {
            Logger.i("GoogleMapMapView: setGestureEnabled: googleMap is not ready to use");
            return;
        }
        googleMap.getUiSettings().setAllGesturesEnabled(enabled);
    }

    @Override
    public void setOnMapClickListener(Runnable clickListener) {
        if (googleMap == null) {
            Logger.i("GoogleMapMapView: setOnMapClickListener: googleMap is not ready to use");
            return;
        }
        googleMap.setOnMapClickListener((latLng) -> clickListener.run());
    }

    @Override
    public void setOnMapLongClickListener(Runnable clickListener) {
        if (googleMap == null) {
            Logger.i("GoogleMapMapView: setOnMapLongClickListener: googleMap is not ready to use");
            return;
        }
        googleMap.setOnMapLongClickListener((latLng) -> clickListener.run());
    }

    @Override
    public void setOnMapReadyCallback(@Nullable Runnable callback) {
        this.onMapReadyCallback = callback;
    }

    @Override
    public void addMarker(long id, Bitmap icon, @NonNull LatLngWrapper latLngWrapper, @Nullable Float precision) {
        if (googleMap == null) {
            Logger.i("GoogleMapMapView: addMarker: googleMap is not ready to use");
            return;
        }

        if (markersByIdHashMap.containsKey(id)) {
            Logger.d("GoogleMapMapView: addMarker: adding a symbol for an existing id !");
            removeMarker(id);
        }

        Marker marker = googleMap.addMarker(new MarkerOptions()
                .position(latLngWrapper.toGoogleMaps())
                .icon(BitmapDescriptorFactory.fromBitmap(icon))
                .anchor(.5f, .5f)
                .zIndex(10));
        markersByIdHashMap.put(id, marker);

        if (precision != null) {
            Circle circle = googleMap.addCircle(new CircleOptions()
                    .center(latLngWrapper.toGoogleMaps())
                    .radius(precision)
                    .strokeWidth(2)
                    .strokeColor(0x440099ff)
                    .fillColor(0x180099ff));
            circlesByIdHashMap.put(id, circle);
        }
    }

    @Override
    public void updateMarker(long id, @NonNull LatLngWrapper latLngWrapper, @Nullable Float precision) {
        if (googleMap == null) {
            Logger.i("MapViewGoogleMapsFragment: called updateMarker before map was initialized");
            return;
        }
        Marker marker = markersByIdHashMap.get(id);
        if (marker != null) {
            marker.setPosition(latLngWrapper.toGoogleMaps());
            if (marker.equals(currentlyCenteredMarker)) {
                centerOnMarker(id, true);
            }
        }
        Circle circle = circlesByIdHashMap.get(id);
        if (circle != null) {
            if (precision != null) {
                circle.setCenter(latLngWrapper.toGoogleMaps());
                circle.setRadius(precision);
            } else {
                circle.remove();
                circlesByIdHashMap.remove(id);
            }
        } else if (precision != null) {
            circle = googleMap.addCircle(new CircleOptions()
                    .center(latLngWrapper.toGoogleMaps())
                    .radius(precision)
                    .strokeWidth(2)
                    .strokeColor(0x440099ff)
                    .fillColor(0x180099ff));
            circlesByIdHashMap.put(id, circle);
        }
    }

    @Override
    public void removeMarker(long id) {
        if (googleMap == null) {
            Logger.i("MapViewGoogleMapsFragment: called removeMarker before map was initialized");
            return;
        }
        Marker marker = markersByIdHashMap.remove(id);
        if (marker != null) {
            marker.remove();
        }
        Circle circle = circlesByIdHashMap.remove(id);
        if (circle != null) {
            circle.remove();
        }
    }

    @Override
    public void centerOnMarkers(boolean animate, boolean includeMyLocation) {
        if (googleMap == null) {
            Logger.i("MapViewGoogleMapsFragment: called centerOnCurrentSymbols before map was initialized");
            return;
        }

        List<LatLngWrapper> markersPositions = new ArrayList<>();
        for (Marker marker : markersByIdHashMap.values()) {
            markersPositions.add(new LatLngWrapper(marker.getPosition()));
        }

        if (includeMyLocation && googleMap.isMyLocationEnabled()) {
            // we use this deprecated method as we do not want to initialise the FusedLocationProviderClient just for this
            // noinspection deprecation
            Location myLocation = googleMap.getMyLocation();
            //noinspection ConstantConditions // in practice, it's not always non-null...
            if (myLocation != null) {
                markersPositions.add(new LatLngWrapper(myLocation));
            }
        }

        Pair<LatLngWrapper, LatLngWrapper> bounds = computeBounds(markersPositions);
        if (bounds == null) {
            // if no symbols: center on 0 0 0
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(0, 0), 0));
        } else if (bounds.second == null) { // count == 1 || ((latNorth-latSouth < 0.005) && (lonEast-lonWest < 0.005))
            // else center on single symbol
            float zoom = Float.max(DEFAULT_ZOOM, googleMap.getCameraPosition().zoom);
            if (animate) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(bounds.first.toGoogleMaps(), zoom), TRANSITION_DURATION_MS, null);
            } else {
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.first.toGoogleMaps(), zoom));
            }
        } else {
            // arbitrary computing InitialView markers heights, and using it to compute padding :S
            int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 64, getResources().getDisplayMetrics());
            if (animate) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(new LatLngBounds(bounds.first.toGoogleMaps(), bounds.second.toGoogleMaps()), padding), TRANSITION_DURATION_MS, null);
            } else {
                googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(new LatLngBounds(bounds.first.toGoogleMaps(), bounds.second.toGoogleMaps()), padding));
            }
        }

        setCurrentlyCenteredMarker(null);
    }

    @Override
    public void centerOnMarker(long id, boolean animate) {
        centerOnMarker(markersByIdHashMap.get(id), animate);
    }

    private void centerOnMarker(Marker marker, boolean animate) {
        if (googleMap == null) {
            Logger.i("MapViewGoogleMapsFragment: called centerOnMarker before map was initialized");
            return;
        }

        if (marker != null) {
            setCurrentlyCenteredMarker(marker);

            CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngZoom(
                    marker.getPosition(),
                    Float.max(DEFAULT_ZOOM, googleMap.getCameraPosition().zoom)
            );
            if (animate) {
                googleMap.animateCamera(cameraUpdate, TRANSITION_DURATION_MS, null);
            }
            else {
                googleMap.moveCamera(cameraUpdate);
            }
        }
    }

    private void setCurrentlyCenteredMarker(Marker marker) {
        if (googleMap == null) {
            Logger.i("MapViewGoogleMapsFragment: called setCurrentlyCenteredMarker before map was initialized");
            return;
        }

        // unset previously centered marker
        if (currentlyCenteredMarker != null) {
            currentlyCenteredMarker.setZIndex(0F);
        }

        // set new marker as centered
        if (marker != null) {
            marker.setZIndex(10F);
        }

        // update currently centered marker
        currentlyCenteredMarker = marker;
    }
}

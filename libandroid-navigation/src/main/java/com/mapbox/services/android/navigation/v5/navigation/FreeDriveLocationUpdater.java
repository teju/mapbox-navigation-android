package com.mapbox.services.android.navigation.v5.navigation;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;

import com.mapbox.android.core.location.LocationEngine;
import com.mapbox.android.core.location.LocationEngineCallback;
import com.mapbox.android.core.location.LocationEngineRequest;
import com.mapbox.android.core.location.LocationEngineResult;
import com.mapbox.geojson.Point;
import com.mapbox.navigator.FixLocation;
import com.mapbox.navigator.NavigationStatus;

import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

class FreeDriveLocationUpdater {

  private LocationEngine locationEngine;
  private LocationEngineRequest locationEngineRequest;
  private final NavigationEventDispatcher navigationEventDispatcher;
  private final MapboxNavigator mapboxNavigator;
  private final OfflineNavigator offlineNavigator;
  private final ScheduledExecutorService executorService;
  private final CurrentLocationEngineCallback callback = new CurrentLocationEngineCallback(this);
  private ScheduledFuture future;
  private Location rawLocation;
  private Handler handler = new Handler(Looper.getMainLooper());

  FreeDriveLocationUpdater(LocationEngine locationEngine, LocationEngineRequest locationEngineRequest,
                           NavigationEventDispatcher navigationEventDispatcher, MapboxNavigator mapboxNavigator,
                           OfflineNavigator offlineNavigator, ScheduledExecutorService executorService) {
    this.locationEngine = locationEngine;
    this.locationEngineRequest = locationEngineRequest;
    this.navigationEventDispatcher = navigationEventDispatcher;
    this.mapboxNavigator = mapboxNavigator;
    this.offlineNavigator = offlineNavigator;
    this.executorService = executorService;
  }

  void configure(@NonNull String tilePath, @NonNull OnOfflineTilesConfiguredCallback onOfflineTilesConfiguredCallback) {
    offlineNavigator.configure(tilePath, onOfflineTilesConfiguredCallback);
  }

  void start() {
    if (future == null) {
      locationEngine.requestLocationUpdates(locationEngineRequest, callback, null);
      future = executorService.scheduleAtFixedRate(new Runnable() {
        @Override
        public void run() {
          if (rawLocation != null) {
            final Location enhancedLocation = getLocation(new Date(), 1500, rawLocation);
            handler.post(new Runnable() {
              @Override
              public void run() {
                navigationEventDispatcher.onEnhancedLocationUpdate(enhancedLocation);
              }
            });
          }
        }
      }, 1500, 1000, TimeUnit.MILLISECONDS);
    }
  }

  void stop() {
    if (future != null) {
      stopLocationUpdates();
    }
  }

  void kill() {
    if (future != null) {
      stopLocationUpdates();
    }
    executorService.shutdown();
  }

  void updateLocationEngine(@NonNull LocationEngine locationEngine) {
    ScheduledFuture currentFuture = future;
    stop();
    this.locationEngine = locationEngine;
    if (currentFuture != null) {
      start();
    }
  }

  void updateLocationEngineRequest(@NonNull LocationEngineRequest request) {
    ScheduledFuture currentFuture = future;
    stop();
    this.locationEngineRequest = request;
    if (currentFuture != null) {
      start();
    }
  }

  private Location getLocation(@NonNull Date date, long lagMillis, @Nullable Location rawLocation) {
    NavigationStatus status = mapboxNavigator.retrieveStatus(date, lagMillis);
    return getMapMatchedLocation(status, rawLocation);
  }

  private Location getMapMatchedLocation(@NonNull NavigationStatus status, @Nullable Location fallbackLocation) {
    Location snappedLocation = new Location(fallbackLocation);
    snappedLocation.setProvider("enhanced");
    FixLocation fixLocation = status.getLocation();
    Point coordinate = fixLocation.getCoordinate();
    snappedLocation.setLatitude(coordinate.latitude());
    snappedLocation.setLongitude(coordinate.longitude());
    Float bearing = fixLocation.getBearing();
    if (bearing != null) {
      snappedLocation.setBearing(bearing);
    }
    snappedLocation.setTime(fixLocation.getTime().getTime());
    return snappedLocation;
  }

  private void stopLocationUpdates() {
    locationEngine.removeLocationUpdates(callback);
    if (future != null) {
      future.cancel(false);
    }
    future = null;
  }

  private void onLocationChanged(@Nullable final Location location) {
    if (location != null) {
      rawLocation = location;
      executorService.execute(new Runnable() {
        @Override
        public void run() {
          mapboxNavigator.updateLocation(location);
        }
      });
    }
  }

  private static class CurrentLocationEngineCallback implements LocationEngineCallback<LocationEngineResult> {

    private final WeakReference<FreeDriveLocationUpdater> updaterWeakReference;

    CurrentLocationEngineCallback(FreeDriveLocationUpdater locationUpdater) {
      this.updaterWeakReference = new WeakReference<>(locationUpdater);
    }

    @Override
    public void onSuccess(LocationEngineResult result) {
      FreeDriveLocationUpdater locationUpdater = updaterWeakReference.get();
      if (locationUpdater != null) {
        Location location = result.getLastLocation();
        locationUpdater.onLocationChanged(location);
      }
    }

    @Override
    public void onFailure(@NonNull Exception exception) {
      Timber.e(exception);
    }
  }
}
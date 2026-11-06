package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.SupportMapFragment

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private lateinit var mMap: MapLibreMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    override fun hasMarker(): Boolean {
        return mMarker != null
    }
    private fun updateMarker(it: LatLng) {
        if (mMarker == null) {
            mMarker = mMap.addMarker(
                MarkerOptions().position(it)
            )
        } else {
            mMarker?.position = it
        }
    }
    private fun removeMarker() {
        mMarker?.remove()
        mMarker = null
    }
    private fun syncLocationIfStarted() {
        if (viewModel.isStarted) {
            viewModel.update(true, lat, lon)
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.collect { value ->
                    showStartNotification(value)
                }
            }
        }
    }
    override fun initializeMap() {
        val key = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData.getString("com.maplibre.AccessToken")
        MapLibre.getInstance(this, key, WellKnownTileServer.Mapbox)
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng?.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng)
                        .zoom(12.0f.toDouble())
                        .bearing(0f.toDouble())
                        .tilt(0f.toDouble())
                        .build()
                ))
                updateMarker(latLng)
                syncLocationIfStarted()
            }
        }
    }
    override fun onMapReady(mapLibreMap: MapLibreMap) {
        mMap = mapLibreMap
        with(mMap){


            // maplibre custom ui
            var typeUrl = "https://demotiles.maplibre.org/style.json"
            if (viewModel.mapType.equals(2)) { // Satellite
                typeUrl = "mapbox://styles/mapbox/satellite-streets-v12"
            } else if (viewModel.mapType.equals(3)) { // Terrain
                typeUrl = "mapbox://styles/mapbox/outdoors-v12"
            } else if (viewModel.mapType.equals(4)) { // Hybrid
                typeUrl = "mapbox://styles/mapbox/navigation-day-v1"
            } else {
                typeUrl = "mapbox://styles/mapbox/streets-v12"
            }
            setStyle(typeUrl) { style ->
                if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { 
                    val locationComponent = mMap.locationComponent
                    locationComponent.activateLocationComponent(
                        LocationComponentActivationOptions.builder(this@MapActivity, style)
                        .useDefaultLocationEngine(true)
                        .build()
                    )
                    locationComponent.isLocationComponentEnabled = true
                    locationComponent.cameraMode = CameraMode.NONE
                    locationComponent.renderMode = RenderMode.COMPASS
                } else {
                    ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
                }
            }
            uiSettings.setAllGesturesEnabled(true)
            uiSettings.setCompassEnabled(true)
            uiSettings.setCompassMargins(0,480,120,0)
            uiSettings.setLogoEnabled(true)
            uiSettings.setLogoMargins(0,0,0,80)
            uiSettings.setAttributionEnabled(false)


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng?.let {
                if (viewModel.isStarted) {
                    updateMarker(it)
                } else {
                    removeMarker()
                }
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom.toDouble()))
            }

            addOnMapClickListener(this@MapActivity)
        }
    }
    override fun onMapClick(latLng: LatLng): Boolean {
        mLatLng = latLng
        updateMarker(latLng)
        mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        lat = latLng.latitude
        lon = latLng.longitude
        syncLocationIfStarted()
        return true
    }



    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}

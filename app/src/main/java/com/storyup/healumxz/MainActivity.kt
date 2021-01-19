package com.storyup.healumxz

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.Sensor.TYPE_HEART_RATE
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import androidx.annotation.WorkerThread
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientModeSupport
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

private const val PERMISSIONS_REQUEST = 66
private const val PERMISSION_PATH = "/permission"
private const val STOP_PATH = "/stop"
private const val STOPPED_PATH = "/stopped"
private const val PLAY_PATH = "/start"
private const val PLAYED_PATH = "/started"
private const val FINISH_PATH = "/finish"
private const val AUTHORIZE_PATH = "/authorize"
private const val HEART_RATE_PATH = "/heart-rate"

class MainActivity : FragmentActivity(), AmbientModeSupport.AmbientCallbackProvider,
        SensorEventListener2, MessageClient.OnMessageReceivedListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AmbientModeSupport.attach(this)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        startButton = findViewById(R.id.actionStart)
        startButton.setOnClickListener {
            if (!isBodySensorPermissionGranted()) {
                requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), PERMISSIONS_REQUEST)
                return@setOnClickListener
            }
            registerListener()
        }

        Wearable.getMessageClient(this).addListener(this)
    }

    private fun registerListener() {
        val heartRateSensor = sensorManager.getDefaultSensor(TYPE_HEART_RATE)
        sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
        sendMessageToHandheld(PLAYED_PATH)
    }

    private fun unregisterListener() {
        sensorManager.unregisterListener(this)
        sendMessageToHandheld(STOPPED_PATH)
    }

    private fun isBodySensorPermissionGranted(): Boolean =
            checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type != TYPE_HEART_RATE) return@let

            val putDataReq: PutDataRequest = PutDataMapRequest.create(HEART_RATE_PATH).run {
                dataMap.putFloat(HEART_RATE_PATH, it.values[0])
                asPutDataRequest()
            }
            Wearable.getDataClient(this).putDataItem(putDataReq)
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            STOP_PATH -> unregisterListener()
            PLAY_PATH -> registerListener()
            FINISH_PATH -> finish()
            AUTHORIZE_PATH -> {
                if (!isBodySensorPermissionGranted()) {
                    requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), PERMISSIONS_REQUEST)
                } else {
                    sendMessageToHandheld(PERMISSION_PATH, DataMap().apply {
                        putInt(PERMISSION_PATH, 2)
                    })
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onFlushCompleted(sensor: Sensor?) {}

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return object : AmbientModeSupport.AmbientCallback() {
            override fun onEnterAmbient(ambientDetails: Bundle?) {
                super.onEnterAmbient(ambientDetails)
            }

            override fun onExitAmbient() {
                super.onExitAmbient()
            }
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST) {
            return
        }
        // Send info to handheld
        sendMessageToHandheld(PERMISSION_PATH, DataMap().apply {
            val result = if (grantResults[0] == PackageManager.PERMISSION_GRANTED) 2 else 1
            putInt(PERMISSION_PATH, result)
        })
    }

    private fun sendMessageToHandheld(path: String, data: DataMap? = null) {
        lifecycleScope.launch(CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
        }) {
            val nodes = withContext(Dispatchers.IO) { getNodes() }
            val nearbyNodeId = pickBestNodeId(nodes) ?: return@launch
            Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(nearbyNodeId, path, data?.toByteArray())
        }
    }

    private fun pickBestNodeId(nodes: Set<Node>): String? {
        // Find a nearby node or pick one arbitrarily
        return nodes.firstOrNull { it.isNearby }?.id ?: nodes.firstOrNull()?.id
    }

    @WorkerThread
    private fun getNodes(): Set<Node> {
        val results = HashSet<Node>()
        val nodeListTask = Wearable.getNodeClient(this).connectedNodes
        try {
            // Block on a task and get the result synchronously (because this is on a background thread).
            val nodes = Tasks.await(nodeListTask)
            nodes.forEach {
                results.add(it)
            }
        } catch (exception: ExecutionException) {
            exception.printStackTrace()
        } catch (exception: InterruptedException) {
            exception.printStackTrace()
        }
        return results
    }
}
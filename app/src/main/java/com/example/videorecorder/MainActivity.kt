package com.example.videorecorder

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FlashMode
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentAddress = "定位中..."
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var segmentTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillSize(), color = MaterialTheme.colorScheme.background) {
                    VideoRecorderUI(
                        startRecord = { q, d, m, b -> startRecord(q, d, m, b) },
                        stopRecord = ::stopRecord,
                        updateLocation = ::getLocation
                    )
                }
            }
        }
    }

    private fun getLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            loc ?: return@addOnSuccessListener
            val geocoder = Geocoder(this, Locale.CHINA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { list ->
                    parseAddress(list)
                }
            } else {
                val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                parseAddress(list ?: emptyList())
            }
        }
    }

    private fun parseAddress(list: List<Address>) {
        if (list.isEmpty()) {
            currentAddress = "定位失败"
            return
        }
        val addr = list[0]
        val city = addr.locality ?: ""
        val district = addr.subLocality ?: ""
        val road = addr.thoroughfare ?: ""
        currentAddress = "$city$district$road"
    }

    private fun startRecord(quality: Quality, duration: Int, mute: Boolean, bitrate: Float) {
        stopRecord()
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val audio = if (mute) AudioConfig.create(false) else AudioConfig.create(true)
        val name = "REC_${System.currentTimeMillis()}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoRecorder")
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        val output = androidx.camera.video.MediaStoreOutputOptions.Builder(contentResolver, uri).build()

        recording = videoCapture?.output?.prepareRecording(this, output)
            ?.withAudioEnabled(audio)
            ?.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    runOnUiThread { Toast.makeText(this, "分段已保存", Toast.LENGTH_SHORT).show() }
                }
            }

        val ms = TimeUnit.MINUTES.toMillis(duration.toLong())
        segmentTimer = object : CountDownTimer(ms, 1000) {
            override fun onTick(p0: Long) {}
            override fun onFinish() {
                stopRecord()
                startRecord(quality, duration, mute, bitrate)
            }
        }.start()

        runOnUiThread { Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show() }
    }

    private fun stopRecord() {
        segmentTimer?.cancel()
        recording?.stop()
        recording = null
    }

    override fun onDestroy() {
        stopRecord()
        super.onDestroy()
    }
}

@Composable
fun VideoRecorderUI(
    startRecord: (Quality, Int, Boolean, Float) -> Unit,
    stopRecord: () -> Unit,
    updateLocation: () -> Unit
) {
    val ctx = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(Quality.FHD) }
    var showQuality by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(5) }
    var mute by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    var bitrate by remember { mutableFloatStateOf(1.0f) }
    var time by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("定位中...") }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!it.all { e -> e.value }) Toast.makeText(ctx, "请授权权限", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        perm.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION))
        updateLocation()
    }

    LaunchedEffect(Unit) {
        while (true) {
            time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            delay(1000)
        }
    }

    val cam = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    val controller = remember {
        CameraController.Builder(ctx).setCameraSelector(cam).setEnabledUseCases(CameraController.VIDEO_CAPTURE).build()
    }.apply { flashMode = if (flash && !front) FlashMode.ON else FlashMode.OFF }

    Box(Modifier.fillSize()) {
        androidx.camera.view.PreviewView(Modifier.fillSize(), controller = controller)

        Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(time, color = Color.White, fontSize = 20.sp)
            Text(addr, color = Color.White, fontSize = 18.sp)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExposedDropdownMenuBox(showQuality, { showQuality = !showQuality }) {
                TextField(
                    when (quality) {
                        Quality.FHD -> "1080P"
                        Quality.HD -> "720P"
                        else -> "480P"
                    },
                    {},
                    readOnly = true,
                    label = { Text("分辨率") },
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(showQuality, { showQuality = false }) {
                    DropdownMenuItem({ Text("1080P") }, { quality = Quality.FHD; showQuality = false })
                    DropdownMenuItem({ Text("720P") }, { quality = Quality.HD; showQuality = false })
                    DropdownMenuItem({ Text("480P") }, { quality = Quality.SD; showQuality = false })
                }
            }

            TextField(duration.toString(), { duration = it.toIntOrNull() ?: 5 }, label = { Text("分段时长(分钟)") })
            TextField(bitrate.toString(), { bitrate = it.toFloatOrNull() ?: 1f }, label = { Text("码率倍率 0.5~2.0") })

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("静音")
                Switch(mute, { mute = it })
                Text("闪光灯")
                Switch(flash, { flash = it })
                Text("前置")
                Switch(front, { front = it })
            }

            Button({
                if (isRecording) stopRecord() else startRecord(quality, duration, mute, bitrate)
                isRecording = !isRecording
            }) {
                Text(if (isRecording) "停止" else "开始录制")
            }
        }
    }
}
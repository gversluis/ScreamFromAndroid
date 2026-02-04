package nodomain.scream.screamfromandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main activity to control Scream audio streaming
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1
        private const val REQUEST_RECORD_AUDIO = 2
    }

    private var permissionLauncher: ActivityResultLauncher<Array<String>?> = getRequestPermissionsLauncher()
    private lateinit var serverIpEditText: EditText
    private lateinit var serverPortEditText: EditText
    private lateinit var button: Button
    private lateinit var channels: RadioGroup
    private lateinit var mute: CheckBox

    private var mediaProjectionResultCode: Int = -1
    private var mediaProjectionResultData: Intent? = null
    private var isStreaming = false

    fun EditText.getTrimmedString():String { return this.text.toString().trim() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = this.applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE)
        serverIpEditText = findViewById(R.id.serverIpEditText)
        serverPortEditText = findViewById(R.id.serverPortEditText)
        button = findViewById(R.id.button)
        channels = findViewById( R.id.channels)
        mute = findViewById( R.id.mute)

        // load saved values or fall back to default
        serverIpEditText.setText(prefs.getString("SCREAM_IP", ScreamAudioService.SCREAM_IP))
        serverPortEditText.setText(prefs.getInt("SCREAM_PORT", ScreamAudioService.SCREAM_PORT).toString())
        channels.check(when (prefs.getInt("SCREAM_CHANNELS", 2)) {
            1 ->  R.id.mono
            else -> R.id.stereo
        })
        mute.isChecked = prefs.getBoolean("SCREAM_MUTE", true)

        // save on changes
        serverIpEditText.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { if (serverIpEditText.getTrimmedString() == "") serverIpEditText.setText(ScreamAudioService.SCREAM_IP); prefs.edit().putString("SCREAM_IP", serverIpEditText.getTrimmedString()).apply() }}
        serverPortEditText.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { if (serverPortEditText.getTrimmedString() == "") serverPortEditText.setText(ScreamAudioService.SCREAM_PORT.toString()); prefs.edit().putInt("SCREAM_PORT", serverPortEditText.getTrimmedString().toInt()).apply() }}
        channels.setOnCheckedChangeListener { group, checkedId ->
            val channels = when (checkedId) {
                R.id.mono -> 1
                else -> 2   // stereo = default
            }
            prefs.edit().putInt("SCREAM_CHANNELS", channels).apply()
        }
        mute.setOnCheckedChangeListener { buttonView, isChecked -> prefs.edit().putBoolean("SCREAM_MUTE", isChecked).apply()        }

        updateButtonStates()
    }

    fun hasPermissions(): Boolean {
        return getMissingPermissions().isEmpty()
    }

    fun getPermissions(): MutableList<String> {
        val runtimePermissions = mutableListOf<String>()

        // RECORD_AUDIO is runtime
        runtimePermissions.add(Manifest.permission.RECORD_AUDIO)

        // POST_NOTIFICATIONS is runtime on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // FOREGROUND_SERVICE_MEDIA_PROJECTION is runtime on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runtimePermissions.add(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
        }
        return runtimePermissions
    }

    fun getMissingPermissions(): Array<String> {
        val runtimePermissions = getPermissions()
        // Filter only not granted permissions
        val notGranted = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        return notGranted
    }

    fun getRequestPermissionsLauncher(): ActivityResultLauncher<Array<String>?> {
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            results.forEach { (perm, runtimePermissions) ->
                if (!runtimePermissions) {
                    Toast.makeText(
                        this,
                        "Required permission denied: $perm",
                        Toast.LENGTH_SHORT
                    ).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                }
            }
            updateButtonStates()
        }
        return permissionLauncher
    }
    fun requestPermissions() {
        permissionLauncher.launch(getMissingPermissions())
    }

    private fun requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO
                )
            }
        }
    }

    private fun startStreaming() {
        requestPermissions()
        val serverIp = serverIpEditText.getTrimmedString()
        val portStr = serverPortEditText.getTrimmedString()

        if (serverIp.isEmpty()) {
            Toast.makeText(this, "Please enter server IP address", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            portStr.toInt()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show()
            return
        }

        // Check audio permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Audio recording permission required", Toast.LENGTH_SHORT).show()
                requestAudioPermission()
                return
            }
        }

        // Request MediaProjection for audio capture
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
    }

    private fun stopStreaming() {
        val intent = Intent(this, ScreamAudioService::class.java).apply {
            action = "STOP_CAPTURE"
        }
        startService(intent)

        isStreaming = false
        updateButtonStates()
        Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjectionResultCode = resultCode
                mediaProjectionResultData = data

                val serverIp = serverIpEditText.getTrimmedString()
                val port = serverPortEditText.getTrimmedString().toIntOrNull() ?: 4010

                val intent = Intent(this, ScreamAudioService::class.java).apply {
                    action = "START_CAPTURE"
                    putExtra("SERVER_IP", serverIp)
                    putExtra("SERVER_PORT", port)
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("RESULT_DATA", data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                isStreaming = true
                updateButtonStates()
                Toast.makeText(this, "Streaming started to $serverIp:$port", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Audio permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Audio permission is required for streaming",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateButtonStates() {
        serverIpEditText.isEnabled = !isStreaming
        serverPortEditText.isEnabled = !isStreaming
        for (i in 0 until channels.childCount) channels.getChildAt(i).isEnabled = !isStreaming
        mute.isEnabled = !isStreaming

        if (!hasPermissions()) {
            button.setBackgroundColor(TypedValue().also { theme.resolveAttribute(com.google.android.material.R.attr.colorButtonNormal, it, true) }.data)
            button.setText("Request audio permissions")
            button.setOnClickListener { requestPermissions() }
        } else if (isStreaming) {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.stop))
            button.setText("Stop streaming")
            button.setOnClickListener { stopStreaming() }
        } else {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.start))
            button.setText("Start streaming")
            button.setOnClickListener { startStreaming() }
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        if (isStreaming) {
            stopStreaming()
        }
    }
}
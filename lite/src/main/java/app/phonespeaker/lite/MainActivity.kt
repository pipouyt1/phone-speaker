package app.phonespeaker.lite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** The whole UI: pair, start or stop, and the battery setting. Built in code, no libraries. */
class MainActivity : Activity() {
    private lateinit var cfg: Config
    private val main = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var pairBox: LinearLayout
    private lateinit var url: EditText
    private lateinit var code: EditText
    private lateinit var pairButton: Button
    private lateinit var pairError: TextView
    private lateinit var runButton: Button
    private lateinit var unpairButton: Button
    private lateinit var batteryNote: TextView
    private lateinit var batteryButton: Button

    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        column.addView(text(24f, bold = true).apply { this.text = "Phone Speaker Lite" })
        column.addView(text(14f).apply { this.text = "Usa este dispositivo como parlante de Home Assistant. Funciona con la pantalla apagada y gasta poca batería." })
        status = text(18f, bold = true)
        column.addView(status)

        pairBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        url = EditText(this).apply {
            hint = "Dirección de Home Assistant (ej. 192.168.1.50:8123)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(cfg.baseUrl)
        }
        code = EditText(this).apply {
            hint = "Código de emparejamiento"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        pairError = text(14f).apply { setTextColor(0xFFB3261E.toInt()) }
        pairButton = Button(this).apply {
            this.text = "Emparejar"
            setOnClickListener { pair() }
        }
        pairBox.addView(url)
        pairBox.addView(code)
        pairBox.addView(pairError)
        pairBox.addView(pairButton)
        column.addView(pairBox)

        runButton = Button(this).apply { setOnClickListener { toggle() } }
        column.addView(runButton)

        batteryNote = text(14f).apply {
            this.text = "Para que siga funcionando con la pantalla apagada, quita las restricciones de batería a esta app."
        }
        batteryButton = Button(this).apply {
            this.text = "Sin restricciones de batería"
            setOnClickListener { batteryExemption() }
        }
        column.addView(batteryNote)
        column.addView(batteryButton)

        unpairButton = Button(this).apply {
            this.text = "Desemparejar"
            setOnClickListener {
                SpeakerService.stop(this@MainActivity)
                cfg.unpair()
                render()
            }
        }
        column.addView(unpairButton)
        return ScrollView(this).apply { addView(column) }
    }

    private fun render() {
        val paired = cfg.paired
        pairBox.visibility = if (paired) View.GONE else View.VISIBLE
        runButton.visibility = if (paired) View.VISIBLE else View.GONE
        unpairButton.visibility = if (paired) View.VISIBLE else View.GONE
        runButton.text = if (SpeakerService.running) "Detener" else "Iniciar"
        status.text = when {
            !paired -> "Sin emparejar"
            !SpeakerService.running -> "Detenido"
            SpeakerService.connected -> "Conectado a Home Assistant"
            else -> SpeakerService.lastError ?: "Conectando..."
        }
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !unrestricted()
        batteryNote.visibility = if (restricted) View.VISIBLE else View.GONE
        batteryButton.visibility = if (restricted) View.VISIBLE else View.GONE
    }

    private fun toggle() {
        if (SpeakerService.running) {
            cfg.userStopped = true
            SpeakerService.stop(this)
        } else {
            cfg.userStopped = false
            SpeakerService.start(this)
        }
        main.postDelayed({ render() }, 300L)
    }

    private fun pair() {
        val base = HaClient.normalizeUrl(url.text.toString())
        val pin = code.text.toString().trim()
        if (base.isEmpty() || pin.isEmpty()) {
            pairError.text = "Escribe la dirección y el código"
            return
        }
        pairError.text = ""
        pairButton.isEnabled = false
        thread {
            val result = HaClient.pair(cfg, base, pin)
            main.post {
                pairButton.isEnabled = true
                result.onSuccess {
                    cfg.baseUrl = base
                    cfg.token = it
                    cfg.userStopped = false
                    SpeakerService.start(this)
                    render()
                }.onFailure { pairError.text = it.message }
            }
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.M)
    private fun unrestricted(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @android.annotation.SuppressLint("BatteryLife")
    private fun batteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
}

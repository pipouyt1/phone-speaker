package app.phonespeaker

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var cfg: Config

    private lateinit var address: EditText
    private lateinit var code: EditText
    private lateinit var pairButton: Button
    private lateinit var smartSwitch: Switch
    private lateinit var toggle: Button
    private lateinit var unpair: Button
    private lateinit var status: TextView

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            status.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT), 1
        )

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        address = EditText(this).apply {
            hint = "Dirección de Home Assistant (ej. 192.168.1.50)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(cfg.baseUrl)
        }
        code = EditText(this).apply {
            hint = "Código de emparejamiento"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        pairButton = Button(this).apply {
            text = "Emparejar"
            setOnClickListener { onPair() }
        }
        smartSwitch = Switch(this).apply {
            text = "Modo inteligente (selector de salida y batería)"
            isChecked = cfg.smart
            setOnCheckedChangeListener { _, checked ->
                cfg.smart = checked
                // the mode is sent when connecting: reconnect so Home Assistant updates its entities
                if (SpeakerService.running) {
                    stopService(Intent(this@MainActivity, SpeakerService::class.java))
                    startSpeaker()
                }
            }
        }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        unpair = Button(this).apply {
            text = "Desemparejar"
            setOnClickListener {
                stopService(Intent(this@MainActivity, SpeakerService::class.java))
                cfg.unpair()
                refresh()
            }
        }
        status = TextView(this).apply { setPadding(0, pad, 0, 0) }

        listOf(address, code, pairButton, smartSwitch, toggle, unpair, status).forEach(root::addView)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresher.run()
    }

    override fun onPause() {
        status.removeCallbacks(refresher)
        super.onPause()
    }

    private fun onPair() {
        val url = HaClient.normalizeUrl(address.text.toString())
        val pairingCode = code.text.toString().trim()
        if (url.isEmpty() || pairingCode.isEmpty()) {
            status.text = "Escribe la dirección y el código."
            return
        }
        status.text = "Emparejando..."
        thread {
            val result = HaClient.pair(cfg, url, pairingCode)
            runOnUiThread {
                result
                    .onSuccess { token ->
                        cfg.baseUrl = url
                        cfg.token = token
                        code.text.clear()
                        startSpeaker()
                    }
                    .onFailure { status.text = it.message }
            }
        }
    }

    private fun startSpeaker() {
        ContextCompat.startForegroundService(this, Intent(this, SpeakerService::class.java))
        status.postDelayed({ refresh() }, 500)
    }

    private fun onToggle() {
        if (SpeakerService.running) {
            stopService(Intent(this, SpeakerService::class.java))
            status.postDelayed({ refresh() }, 300)
        } else {
            startSpeaker()
        }
    }

    private fun refresh() {
        val paired = cfg.paired
        val pairing = if (paired) View.GONE else View.VISIBLE
        val operating = if (paired) View.VISIBLE else View.GONE
        address.visibility = pairing
        code.visibility = pairing
        pairButton.visibility = pairing
        smartSwitch.visibility = operating
        toggle.visibility = operating
        unpair.visibility = operating

        if (!paired) {
            if (status.text.isNullOrEmpty() || status.text == "Detenido.") {
                status.text = "En Home Assistant: Ajustes > Dispositivos y servicios > Añadir integración > " +
                    "Phone Speaker. Te mostrará un código para escribir aquí."
            }
            return
        }
        toggle.text = if (SpeakerService.running) "Detener" else "Iniciar"
        val c = SpeakerService.client
        status.text = when {
            !SpeakerService.running -> "Detenido."
            c?.connected == true -> "Conectado a ${cfg.baseUrl}."
            else -> "Conectando a ${cfg.baseUrl}...\n${c?.lastError.orEmpty()}"
        }
    }
}

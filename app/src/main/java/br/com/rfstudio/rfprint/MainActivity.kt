package br.com.rfstudio.rfprint

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var adapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private lateinit var deviceSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var message: EditText
    private var devices: List<BluetoothDevice> = emptyList()
    private val permissionRequest = 410

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = BluetoothAdapter.getDefaultAdapter()
        buildScreen()
        handleShare(intent)
        if (hasBluetoothPermission()) refreshDevices() else requestBluetoothPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 22)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        val header = TextView(this).apply {
            text = "RF PRINT\nImpressão Bluetooth"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(12, 24, 12, 24)
            setBackgroundColor(Color.rgb(16, 42, 67))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            text = "Status: aguardando permissão Bluetooth"
            textSize = 14f
            setTextColor(Color.rgb(40, 60, 75))
            setPadding(4, 18, 4, 12)
        }
        root.addView(status)
        addLabel(root, "1. Selecione a impressora pareada")
        deviceSpinner = Spinner(this)
        root.addView(deviceSpinner, LinearLayout.LayoutParams(-1, -2))
        addButton(root, "ATUALIZAR IMPRESSORAS", Color.rgb(16, 42, 67)) { refreshDevices() }
        addButton(root, "CONECTAR IMPRESSORA", Color.rgb(22, 132, 92)) { connectPrinter() }
        addLabel(root, "2. Texto para imprimir")
        message = EditText(this).apply {
            hint = "Digite aqui o texto da comanda ou comprovante"
            minLines = 5
            gravity = Gravity.TOP
            setTextSize(16f)
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(message, LinearLayout.LayoutParams(-1, 0, 1f))
        addButton(root, "IMPRIMIR TESTE", Color.rgb(242, 140, 40)) { printTest() }
        addButton(root, "IMPRIMIR TEXTO", Color.rgb(16, 42, 67)) { printMessage() }
        val footer = TextView(this).apply {
            text = "RF Studio Gravação e Divulgação"
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(0, 12, 0, 0)
        }
        root.addView(footer)
        setContentView(root)
    }

    private fun addLabel(root: LinearLayout, value: String) {
        root.addView(TextView(this).apply {
            text = value; textSize = 15f; setTextColor(Color.rgb(16, 42, 67));
            setPadding(2, 14, 2, 6)
        })
    }

    private fun addButton(root: LinearLayout, label: String, color: Int, action: () -> Unit) {
        val button = Button(this).apply {
            text = label; setTextColor(Color.WHITE); setBackgroundColor(color)
            isAllCaps = false; textSize = 15f; setOnClickListener { action() }
        }
        val lp = LinearLayout.LayoutParams(-1, 52)
        lp.topMargin = 7
        root.addView(button, lp)
    }

    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), permissionRequest)
        else refreshDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) refreshDevices()
        else setStatus("Permissão Bluetooth não concedida")
    }

    private fun refreshDevices() {
        if (!hasBluetoothPermission()) { requestBluetoothPermission(); return }
        val bt = adapter
        if (bt == null) { setStatus("Este aparelho não possui Bluetooth"); return }
        if (!bt.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 411)
            setStatus("Ative o Bluetooth para continuar")
            return
        }
        try {
            devices = bt.bondedDevices.sortedBy { it.name ?: it.address }
            val labels = devices.map { "${it.name ?: "Dispositivo Bluetooth"}  •  ${it.address}" }
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("Nenhuma impressora pareada") })
            setStatus(if (devices.isEmpty()) "Nenhum dispositivo pareado. Pareie a impressora nas configurações do Android." else "${devices.size} dispositivo(s) pareado(s). Selecione a impressora.")
        } catch (e: SecurityException) { setStatus("Sem permissão para acessar o Bluetooth") }
    }

    private fun connectPrinter() {
        if (!hasBluetoothPermission()) { requestBluetoothPermission(); return }
        if (devices.isEmpty()) { refreshDevices(); return }
        val selected = devices.getOrNull(deviceSpinner.selectedItemPosition) ?: return
        setStatus("Conectando a ${selected.name ?: selected.address}...")
        thread {
            try {
                socket?.close()
                val newSocket = selected.createRfcommSocketToServiceRecord(sppUuid)
                adapter?.cancelDiscovery()
                newSocket.connect()
                socket = newSocket
                output = newSocket.outputStream
                runOnUiThread { setStatus("Conectado: ${selected.name ?: selected.address}") }
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null; output = null
                runOnUiThread { setStatus("Falha na conexão. Confira se a impressora usa Bluetooth SPP/ESC-POS e tente novamente.") }
            }
        }
    }

    private fun printTest() {
        val sample = "RF PRINT\nTESTE DE IMPRESSAO\n------------------------------\nImpressora: Oasis OIA-8373\nBluetooth: MTP-3-69DF\nLargura: bobina 80 mm\n\nSe esta mensagem saiu corretamente, a comunicacao basica funcionou.\n\n\n"
        sendToPrinter(sample)
    }

    private fun printMessage() {
        val value = message.text.toString().trim()
        if (value.isEmpty()) { setStatus("Digite um texto antes de imprimir"); return }
        sendToPrinter(value + "\n\n\n")
    }

    private fun sendToPrinter(value: String) {
        val stream = output
        if (stream == null || socket?.isConnected != true) { setStatus("Impressora desconectada. Toque em CONECTAR IMPRESSORA."); return }
        setStatus("Enviando impressão...")
        thread {
            try {
                val bytes = value.toByteArray(Charsets.UTF_8)
                stream.write(byteArrayOf(0x1B, 0x40)) // ESC @ initialize
                stream.write(byteArrayOf(0x1B, 0x61, 0x00)) // align left
                stream.write(bytes)
                stream.flush()
                runOnUiThread { setStatus("Dados enviados à impressora") }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Erro ao imprimir: verifique a conexão e a bobina") }
            }
        }
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (shared != null && ::message.isInitialized) message.setText(shared)
        }
    }

    private fun setStatus(value: String) {
        runOnUiThread { if (::status.isInitialized) status.text = "Status: $value" }
    }

    override fun onDestroy() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

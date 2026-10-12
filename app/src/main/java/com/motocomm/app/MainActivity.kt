package com.motocomm.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    @Volatile private var isStreaming = false
    private var audioSocket: DatagramSocket? = null
    private var streamingJob: Job? = null
    private val PORT = 50000
    private val SAMPLE_RATE = 16000

    private lateinit var audioManager: AudioManager
    @Volatile private var targetIp: InetAddress? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) Toast.makeText(this, "Se necesita el micrófono", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent { MotoCommTheme { MainScreen() } }
    }

    private fun setAudioRoute(isSpeaker: Boolean) {
        audioManager.isSpeakerphoneOn = isSpeaker
    }

    private fun getGatewayIp(): InetAddress? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            val ip = dhcpInfo.gateway
            if (ip == 0) return null
            val ipString = "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
            return InetAddress.getByName(ipString)
        } catch (e: Exception) {
            return null
        }
    }

    // TEMA VISUAL MODERNO (Modo Oscuro Elegante)
    @Composable
    fun MotoCommTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Color(0xFF0F172A), // Slate 900
                surface = Color(0xFF1E293B), // Slate 800
                primary = Color(0xFF3B82F6), // Azul moderno
                secondary = Color(0xFF10B981) // Verde esmeralda (Conectado)
            ), 
            content = content
        )
    }

    @Composable
    fun MainScreen() {
        var isConnected by remember { mutableStateOf(false) }
        var isSpeaker by remember { mutableStateOf(false) } // Audífonos por defecto
        var isPilotMode by remember { mutableStateOf(true) }

        LaunchedEffect(isSpeaker) { setAudioRoute(isSpeaker) }

        Scaffold(
            bottomBar = {
                BottomControlBar(
                    isSpeaker = isSpeaker,
                    isPilotMode = isPilotMode,
                    isConnected = isConnected,
                    onSpeakerChange = { isSpeaker = it },
                    onModeChange = { isPilotMode = it }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                
                val statusColor by animateColorAsState(if (isConnected) MaterialTheme.colorScheme.secondary else Color.Gray)
                
                Text("MOTOCOMM", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 3.sp)
                Spacer(modifier = Modifier.height(16.dp))
                
                // Indicador luminoso de estado
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isConnected) "EN LÍNEA - ${if(isPilotMode) "PILOTO" else "COPILOTO"}" else "SISTEMA APAGADO",
                        color = statusColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(80.dp))

                // BOTÓN CENTRAL DE SEGURIDAD (Tocar para conectar, Mantener para desconectar)
                val buttonColor by animateColorAsState(if (isConnected) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary)
                
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .shadow(if (isConnected) 20.dp else 10.dp, CircleShape, spotColor = buttonColor)
                        .clip(CircleShape)
                        .background(buttonColor.copy(alpha = 0.15f))
                        .border(3.dp, buttonColor.copy(alpha = 0.6f), CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isConnected) {
                                        if (startAudioStream(isPilotMode)) isConnected = true
                                    }
                                },
                                onLongPress = {
                                    if (isConnected) {
                                        stopAudioStream()
                                        isConnected = false
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isConnected) "MANTÉN PARA\nDESCONECTAR" else "TOCA PARA\nCONECTAR",
                            color = buttonColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }

    // PANEL INFERIOR MINIMALISTA
    @Composable
    fun BottomControlBar(
        isSpeaker: Boolean,
        isPilotMode: Boolean,
        isConnected: Boolean,
        onSpeakerChange: (Boolean) -> Unit,
        onModeChange: (Boolean) -> Unit
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                Text("RUTA DE AUDIO", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SelectableButton(text = "ALTAVOZ", selected = isSpeaker) { onSpeakerChange(true) }
                    Spacer(modifier = Modifier.width(16.dp))
                    SelectableButton(text = "CASCO", selected = !isSpeaker) { onSpeakerChange(false) }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Text("MODO DE RED", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SelectableButton(text = "PILOTO", selected = isPilotMode, disabled = isConnected) { onModeChange(true) }
                    Spacer(modifier = Modifier.width(16.dp))
                    SelectableButton(text = "COPILOTO", selected = !isPilotMode, disabled = isConnected) { onModeChange(false) }
                }
            }
        }
    }

    @Composable
    fun RowScope.SelectableButton(text: String, selected: Boolean, disabled: Boolean = false, onClick: () -> Unit) {
        val bgColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color(0xFF334155))
        val textColor = if (selected) Color.White else Color.LightGray
        
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f).height(50.dp),
            enabled = !disabled,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = bgColor, disabledContainerColor = bgColor.copy(alpha = 0.5f))
        ) {
            Text(text, color = if(disabled) textColor.copy(alpha = 0.5f) else textColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }

    private fun startAudioStream(isPilot: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        
        if (!isPilot) {
            targetIp = getGatewayIp()
            if (targetIp == null) {
                Toast.makeText(this, "Asegúrate de conectar el Wi-Fi al Piloto", Toast.LENGTH_LONG).show()
                return false
            }
        } else {
            targetIp = null
        }

        isStreaming = true
        try { audioSocket = DatagramSocket(PORT) } catch (e: Exception) { return false }
        
        streamingJob = CoroutineScope(Dispatchers.IO).launch {
            launch { receiveAudio(isPilot) }
            launch { sendAudio() }
        }
        return true
    }

    private fun stopAudioStream() {
        isStreaming = false
        streamingJob?.cancel()
        audioSocket?.close()
    }

    private fun sendAudio() {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize)
        
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioRecord.audioSessionId)
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioRecord.audioSessionId)

        val packetSize = 1024 
        val buffer = ByteArray(packetSize)
        
        audioRecord.startRecording()
        
        while (isStreaming) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0 && targetIp != null) {
                
                // --- SISTEMA INTELIGENTE VOX (ANTI-CRUCE Y ANTI-FEEDBACK) ---
                var maxAmplitude = 0
                for (i in 0 until read step 2) {
                    // Mide la fuerza de la onda de sonido
                    val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                    val amplitude = abs(sample.toShort().toInt())
                    if (amplitude > maxAmplitude) maxAmplitude = amplitude
                }
                
                // El micrófono está apagado digitalmente. SOLO transmite si hablas 
                // con una fuerza superior a 1000 (el máximo es 32768).
                // Si haces silencio, el otro casco queda limpio, sin cruces.
                if (maxAmplitude > 1000) {
                    try { audioSocket?.send(DatagramPacket(buffer, read, targetIp, PORT)) } catch (e: Exception) {}
                }
            }
        }
        audioRecord.stop()
        audioRecord.release()
    }

    private fun receiveAudio(isPilot: Boolean) {
        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioTrack = AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize, AudioTrack.MODE_STREAM)
        
        val packetSize = 1024
        val buffer = ByteArray(packetSize)

        audioTrack.play()
        while (isStreaming) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                audioSocket?.receive(packet)
                
                if (isPilot && targetIp == null) targetIp = packet.address
                
                audioTrack.write(packet.data, 0, packet.length)
            } catch (e: Exception) {}
        }
        audioTrack.stop()
        audioTrack.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioStream()
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
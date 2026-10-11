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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : ComponentActivity() {

    @Volatile private var isStreaming = false
    private var audioSocket: DatagramSocket? = null
    private var streamingJob: Job? = null
    private val PORT = 50000
    private val SAMPLE_RATE = 16000

    private lateinit var audioManager: AudioManager
    @Volatile private var targetIp: InetAddress? = null // La IP a donde enviaremos el audio

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

    // Función vital: Obtiene la IP del piloto (Gateway) para el copiloto
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

    @Composable
    fun MotoCommTheme(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF121212), primary = Color(0xFFF3BA2F)), content = content)
    }

    @Composable
    fun MainScreen() {
        var estado by remember { mutableStateOf("Desconectado") }
        var isConnected by remember { mutableStateOf(false) }
        var isSpeaker by remember { mutableStateOf(true) }
        var isPilotMode by remember { mutableStateOf(true) }

        LaunchedEffect(isSpeaker) { setAudioRoute(isSpeaker) }

        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("MOTOCOMM", fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Estado: $estado", fontSize = 16.sp, color = if (isConnected) Color(0xFF4CAF50) else Color.Gray)
            
            Spacer(modifier = Modifier.height(30.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(
                    onClick = { isSpeaker = true },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isSpeaker) MaterialTheme.colorScheme.primary else Color.DarkGray),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("🔊 Alta Voz", color = if (isSpeaker) Color.Black else Color.White, fontWeight = FontWeight.Bold) }

                Button(
                    onClick = { isSpeaker = false },
                    colors = ButtonDefaults.buttonColors(containerColor = if (!isSpeaker) MaterialTheme.colorScheme.primary else Color.DarkGray),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("🎧 Moto", color = if (!isSpeaker) Color.Black else Color.White, fontWeight = FontWeight.Bold) }
            }

            Spacer(modifier = Modifier.height(40.dp))
            
            if (!isConnected) {
                Button(
                    onClick = { 
                        isPilotMode = true
                        if (startAudioStream(isPilot = true)) {
                            estado = "Piloto: Esperando voz del copiloto"
                            isConnected = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)) // Verde para Piloto
                ) { Text("MODO PILOTO (Crear Red)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { 
                        isPilotMode = false
                        if (startAudioStream(isPilot = false)) {
                            estado = "Copiloto: Transmitiendo al Piloto"
                            isConnected = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("MODO COPILOTO (Unirse)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black) }

            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp), strokeWidth = 6.dp)
                Spacer(modifier = Modifier.height(40.dp))
                Button(
                    onClick = { 
                        stopAudioStream()
                        estado = "Desconectado"
                        isConnected = false
                    },
                    modifier = Modifier.fillMaxWidth().height(65.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679))
                ) { Text("DESCONECTAR", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }
        }
    }

    private fun startAudioStream(isPilot: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        
        // Logica Unicast
        if (!isPilot) {
            targetIp = getGatewayIp() // El copiloto busca automáticamente la IP del creador de la red
            if (targetIp == null) {
                Toast.makeText(this, "Error: Asegúrate de estar conectado a la Zona Wi-Fi del Piloto", Toast.LENGTH_LONG).show()
                return false
            }
        } else {
            targetIp = null // El Piloto espera a recibir el primer paquete para fijar la IP
        }

        isStreaming = true
        try {
            audioSocket = DatagramSocket(PORT) // Ya no es Broadcast
        } catch (e: Exception) { return false }
        
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

        // FORZAR MICRO-PAQUETES: Obligamos a leer bloques pequeños (1024 bytes) 
        // para destruir la latencia, en lugar de esperar a que se llene el buffer gigante del teléfono.
        val packetSize = 1024 
        val buffer = ByteArray(packetSize)
        
        audioRecord.startRecording()
        
        while (isStreaming) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0 && targetIp != null) {
                try { audioSocket?.send(DatagramPacket(buffer, read, targetIp, PORT)) } catch (e: Exception) {}
            }
        }
        audioRecord.stop()
        audioRecord.release()
    }

    private fun receiveAudio(isPilot: Boolean) {
        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioTrack = AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize, AudioTrack.MODE_STREAM)
        
        // Recibimos en los mismos micro-paquetes rápidos
        val packetSize = 1024
        val buffer = ByteArray(packetSize)

        audioTrack.play()
        while (isStreaming) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                audioSocket?.receive(packet)
                
                if (isPilot && targetIp == null) {
                    targetIp = packet.address
                }
                
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
package com.motocomm.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.util.Log
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

    private var isStreaming = false
    private var audioSocket: DatagramSocket? = null
    private var streamingJob: Job? = null
    private val PORT = 50000
    private val SAMPLE_RATE = 16000 // 16kHz es el estándar para voz clara

    // Solicitar permiso de micrófono en tiempo real
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Se necesita el micrófono para el intercomunicador", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Pedir permiso al abrir
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MotoCommTheme {
                MainScreen()
            }
        }
    }

    @Composable
    fun MotoCommTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                primary = Color(0xFFF3BA2F),
                onPrimary = Color.Black,
                onBackground = Color.White
            ),
            content = content
        )
    }

    @Composable
    fun MainScreen() {
        var estado by remember { mutableStateOf("Desconectado") }
        var isConnected by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "MOTOCOMM", fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Estado: $estado", fontSize = 16.sp, color = if (isConnected) Color(0xFF4CAF50) else Color.Gray)
            
            Spacer(modifier = Modifier.height(60.dp))
            
            if (!isConnected) {
                Button(
                    onClick = { 
                        if (startAudioStream()) {
                            estado = "Transmitiendo en Red Local"
                            isConnected = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(65.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("INICIAR CONEXIÓN (AMBOS)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            } else {
                // Animación de ondas (simplificada con un loader)
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
                ) {
                    Text("DESCONECTAR", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }

    private fun startAudioStream(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Acepta el permiso del micrófono primero", Toast.LENGTH_SHORT).show()
            return false
        }

        isStreaming = true
        try {
            audioSocket = DatagramSocket(PORT)
            audioSocket?.broadcast = true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        streamingJob = CoroutineScope(Dispatchers.IO).launch {
            launch { sendAudio() }
            launch { receiveAudio() }
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

        // Activar filtros de hardware para la moto (Cancelación de eco y ruido)
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioRecord.audioSessionId)
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioRecord.audioSessionId)

        val buffer = ByteArray(minBufSize)
        val broadcastAddress = InetAddress.getByName("255.255.255.255")

        audioRecord.startRecording()

        while (isStreaming && isActive) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                try {
                    val packet = DatagramPacket(buffer, read, broadcastAddress, PORT)
                    audioSocket?.send(packet)
                } catch (e: Exception) {
                    // Ignorar errores menores de envío
                }
            }
        }
        audioRecord.stop()
        audioRecord.release()
    }

    private fun receiveAudio() {
        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioTrack = AudioTrack(
            AudioManager.STREAM_VOICE_CALL, // Audio por el canal de llamadas para max volumen
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize,
            AudioTrack.MODE_STREAM
        )

        val buffer = ByteArray(minBufSize)
        audioTrack.play()

        while (isStreaming && isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                audioSocket?.receive(packet)
                
                // Evitar reproducir nuestro propio audio local (Feedback)
                val senderIP = packet.address.hostAddress
                val myIP = InetAddress.getLocalHost().hostAddress
                if (senderIP != myIP) {
                    audioTrack.write(packet.data, 0, packet.length)
                }
            } catch (e: Exception) {
                // El socket se cerró o hubo timeout
            }
        }
        audioTrack.stop()
        audioTrack.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioStream()
    }
}
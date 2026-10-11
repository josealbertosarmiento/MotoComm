package com.motocomm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MotoCommTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MotoCommTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF121212), 
            surface = Color(0xFF1E1E1E),    
            primary = Color(0xFFF3BA2F),    // Acento amarillo moderno
            onPrimary = Color.Black,
            onBackground = Color.White
        ),
        content = content
    )
}

@Composable
fun MainScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "MOTOCOMM",
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enlace Piloto - Copiloto",
            fontSize = 14.sp,
            color = Color.Gray
        )
        
        Spacer(modifier = Modifier.height(60.dp))
        
        Button(
            onClick = { /* Lógica de Host */ },
            modifier = Modifier.fillMaxWidth().height(65.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("MODO PILOTO (CREAR RED)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Button(
            onClick = { /* Lógica de Cliente */ },
            modifier = Modifier.fillMaxWidth().height(65.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Color.White
            )
        ) {
            Text("MODO COPILOTO (UNIRSE)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
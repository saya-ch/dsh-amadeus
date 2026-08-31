package com.amadeus.whale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.amadeus.whale.theatre.AmbientSound

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefs = AmadeusPrefs.from(this)
    val sound = AmbientSound(this)
    setContent { AppRoot(prefs, sound) }
  }
}
package com.amadeus.whale

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.amadeus.whale.pairing.AmadeusAuthClient
import com.amadeus.whale.pairing.AmadeusPairingService
import com.amadeus.whale.pairing.KeystoreAesGcmCrypto
import com.amadeus.whale.pairing.KeystoreDeviceCredentialStore
import com.amadeus.whale.pairing.NativeAuthClient
import com.amadeus.whale.pairing.PinnedTls
import com.amadeus.whale.pairing.trustAllClient
import com.amadeus.whale.theatre.AmbientSound
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefs = AmadeusPrefs.from(this)
    val sound = AmbientSound(this)
    val credentialStore = KeystoreDeviceCredentialStore(
      SharedPrefsStore(getSharedPreferences("amadeus_device", Context.MODE_PRIVATE)),
      KeystoreAesGcmCrypto("amw_device_v1"),
    )
    val bootstrapClient = trustAllClient()
    val nativeAuth = NativeAuthClient(bootstrapClient) { caDer, instanceId ->
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .sslSocketFactory(PinnedTls.socketFactory(caDer, instanceId), PinnedTls.trustManager(caDer, instanceId))
        .build()
    }
    val authClient = AmadeusAuthClient(nativeAuth, credentialStore, bootstrapClient)
    val pairing = AmadeusPairingService(authClient)
    setContent { AppRoot(prefs, sound, pairing, authClient) }
  }
}

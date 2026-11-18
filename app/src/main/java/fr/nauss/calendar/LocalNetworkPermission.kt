package fr.nauss.calendar

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/** An explicit opt-in for private-IP servers and NAS names resolved by home DNS. */
@Composable internal fun LocalNetworkPermission() {
    if(Build.VERSION.SDK_INT < 37) return
    val context=LocalContext.current
    val permission="android.permission.ACCESS_LOCAL_NETWORK"
    var prompted by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED) }
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted=it;prompted=true }
    if(!granted) {
        OutlinedButton(onClick={request.launch(permission)}) { Text(stringResource(R.string.allow_local_network)) }
        if(prompted) Text(stringResource(R.string.local_network_denied))
    }
}

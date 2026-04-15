package com.example.raspberrycontroller

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class FakeCrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Vibration au lancement : 2 chocs pour simuler un vrai crash
        vibrate()

        setContent {
            FakeCrashScreen(onDismiss = { finish() })
        }
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 180, 80, 320)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }

    override fun onBackPressed() {
        // Bouton retour bloqué
    }
}

@Composable
fun FakeCrashScreen(onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()
    var visible by remember { mutableStateOf(false) }
    var displayedLines by remember { mutableStateOf(listOf<String>()) }
    var tapCount by remember { mutableStateOf(0) }

    // Timestamp réel + PID aléatoire à chaque lancement
    val timestamp = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))
        } else {
            "04-13 14:32:07.421"
        }
    }
    val pid = remember { Random.nextInt(10000, 32000) }

    val allLines = remember {
        """
java.lang.RuntimeException: Unable to start activity ComponentInfo{com.example.raspberrycontroller/com.example.raspberrycontroller.MainActivity}: java.lang.NullPointerException: Attempt to invoke virtual method 'void android.widget.TextView.setText(java.lang.CharSequence)' on a null object reference
    at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3449)
    at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:3601)
    at android.app.ActivityThread.-wrap11(Unknown Source:0)
    at android.app.ActivityThread${'$'}H.handleMessage(ActivityThread.java:1986)
    at android.os.Handler.dispatchMessage(Handler.java:102)
    at android.os.Looper.loop(Looper.java:150)
    at android.app.ActivityThread.main(ActivityThread.java:7425)
    at java.lang.reflect.Method.invoke(Native Method)
    at com.android.internal.os.ZygoteInit${'$'}MethodAndArgsCaller.run(ZygoteInit.java:816)
    at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:706)
Caused by: java.lang.NullPointerException: Attempt to invoke virtual method 'void android.widget.TextView.setText(java.lang.CharSequence)' on a null object reference
    at com.example.raspberrycontroller.MainActivity.onCreate(MainActivity.kt:42)
    at android.app.Activity.performCreate(Activity.java:7893)
    at android.app.Activity.performCreate(Activity.java:7081)
    at android.app.Instrumentation.callActivityOnCreate(Instrumentation.java:1220)
    at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3442)
    ... 9 more
Caused by: kotlinx.coroutines.JobCancellationException: SshClient coroutine cancelled
    at com.example.raspberrycontroller.SshClient.connect(SshClient.kt:88)
    at com.example.raspberrycontroller.MainActivity${'$'}onCreate${'$'}1.invokeSuspend(MainActivity.kt:37)
    at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
    at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:106)
    at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:571)
    at kotlinx.coroutines.scheduling.CoroutineScheduler${'$'}Worker.executeTask(CoroutineScheduler.kt:750)
    at kotlinx.coroutines.scheduling.CoroutineScheduler${'$'}Worker.runWorker(CoroutineScheduler.kt:677)
    at kotlinx.coroutines.scheduling.CoroutineScheduler${'$'}Worker.run(CoroutineScheduler.kt:665)

--------- beginning of system
W/ActivityManager( 1582): Force finishing activity com.example.raspberrycontroller/.MainActivity
I/WindowManager( 1582): WIN DEATH: Window{3f4a2b1 u0 com.example.raspberrycontroller/com.example.raspberrycontroller.MainActivity}
E/InputDispatcher( 1582): channel '3f4a2b1 com.example.raspberrycontroller/MainActivity (server)' ~ Channel is unrecoverably broken and will be disposed!
        """.trimIndent().lines()
    }

    // Affichage ligne par ligne avec scroll automatique
    LaunchedEffect(Unit) {
        visible = true
        delay(300)
        for (line in allLines) {
            displayedLines = displayedLines + line
            delay(55)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                // Timestamp réel
                Text(
                    text = timestamp,
                    color = Color(0xFF555555),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "RaspberryController stopped",
                    color = Color(0xFFFF4444),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "FATAL EXCEPTION: main",
                    color = Color(0xFFFF6B6B),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                // PID aléatoire
                Text(
                    text = "Process: com.example.raspberrycontroller, PID: $pid",
                    color = Color(0xFFFF6B6B),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Stacktrace ligne par ligne — 5 taps pour sortir en secret
                Column(
                    modifier = Modifier.clickable {
                        tapCount++
                        if (tapCount >= 5) onDismiss()
                    }
                ) {
                    displayedLines.forEach { line ->
                        Text(
                            text = line,
                            color = Color(0xFFCCCCCC),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
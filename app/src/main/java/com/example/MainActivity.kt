package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private var activeWebView: WebView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Scaffold(
          modifier = Modifier.fillMaxSize(),
          contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
          VoxelGameScreen(
            modifier = Modifier.padding(innerPadding),
            onWebViewCreated = { webView -> activeWebView = webView }
          )
        }
      }
    }
  }

  override fun onPause() {
    super.onPause()
    activeWebView?.onPause()
    activeWebView?.pauseTimers()
  }

  override fun onResume() {
    super.onResume()
    activeWebView?.onResume()
    activeWebView?.resumeTimers()
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= TRIM_MEMORY_MODERATE) {
      activeWebView?.clearCache(false)
    }
  }

  override fun onLowMemory() {
    super.onLowMemory()
    activeWebView?.clearCache(false)
  }

  override fun onDestroy() {
    activeWebView?.destroy()
    activeWebView = null
    super.onDestroy()
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VoxelGameScreen(
  modifier: Modifier = Modifier,
  onWebViewCreated: (WebView) -> Unit = {}
) {
  val context = LocalContext.current
  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var showControlPanel by remember { mutableStateOf(false) }
  var selectedBiome by remember { mutableStateOf("Plains & Mountains") }
  var isShadowsEnabled by remember { mutableStateOf(true) }
  var isHapticsEnabled by remember { mutableStateOf(true) }

  fun triggerHaptic() {
    if (!isHapticsEnabled) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      vibratorManager?.defaultVibrator?.vibrate(
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
      )
    } else {
      @Suppress("DEPRECATION")
      val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      vibrator?.vibrate(20)
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      webViewRef?.destroy()
    }
  }

  Box(modifier = modifier.fillMaxSize().background(Color(0xFF07090F))) {
    // 3D WebGL Voxel Game WebView Layer
    AndroidView(
      factory = { ctx ->
        WebView(ctx).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          setLayerType(View.LAYER_TYPE_HARDWARE, null)
          isFocusable = true
          isFocusableInTouchMode = true
          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
          }
          webChromeClient = WebChromeClient()
          webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
              super.onPageFinished(view, url)
            }
          }
          loadUrl("file:///android_asset/voxel_game/index.html")
          webViewRef = this
          onWebViewCreated(this)
        }
      },
      modifier = Modifier.fillMaxSize().testTag("game_viewport")
    )

    // Floating Native Tools Bar
    Row(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .statusBarsPadding()
        .padding(top = 8.dp, end = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IconButton(
        onClick = {
          triggerHaptic()
          showControlPanel = !showControlPanel
        },
        modifier = Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(Color(0xCC1A2138))
          .testTag("btn_engine_menu")
      ) {
        Icon(
          imageVector = Icons.Default.Tune,
          contentDescription = "Engine Tuning",
          tint = Color(0xFFFFD60A)
        )
      }
    }

    // Engine Control & Biome Switcher Modal Sheet
    AnimatedVisibility(
      visible = showControlPanel,
      enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
      exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
      modifier = Modifier.align(Alignment.BottomCenter)
    ) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .wrapContentHeight()
          .padding(16.dp)
          .testTag("engine_control_panel"),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xF2121727),
        tonalElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3757))
      ) {
        Column(
          modifier = Modifier
            .padding(20.dp)
            .fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Voxel Engine Dashboard",
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFFFFD60A)
            )
            IconButton(onClick = { showControlPanel = false }) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
              )
            }
          }

          Text(
            text = "World Biome Generator",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFA0AEC0)
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf("Plains", "Desert", "Mountains").forEach { biome ->
              val isSelected = selectedBiome.startsWith(biome)
              Box(
                modifier = Modifier
                  .weight(1f)
                  .clip(RoundedCornerShape(8.dp))
                  .background(if (isSelected) Color(0xFF007AFF) else Color(0xFF1E2842))
                  .clickable {
                    selectedBiome = biome
                    triggerHaptic()
                    webViewRef?.evaluateJavascript("generateNewWorld();", null)
                  }
                  .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = biome,
                  color = Color.White,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium
                )
              }
            }
          }

          HorizontalDivider(color = Color(0xFF26324F))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Haptic Vibration Feedback",
              fontSize = 14.sp,
              color = Color.White
            )
            Switch(
              checked = isHapticsEnabled,
              onCheckedChange = {
                isHapticsEnabled = it
                triggerHaptic()
              }
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Cascaded Shadows & SSAO",
              fontSize = 14.sp,
              color = Color.White
            )
            Switch(
              checked = isShadowsEnabled,
              onCheckedChange = {
                isShadowsEnabled = it
                triggerHaptic()
                webViewRef?.evaluateJavascript("togglePostProcessing($it);", null)
              }
            )
          }

          Button(
            onClick = {
              triggerHaptic()
              webViewRef?.evaluateJavascript("generateNewWorld();", null)
              showControlPanel = false
            },
            modifier = Modifier.fillMaxWidth().testTag("btn_regenerate_world"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
            shape = RoundedCornerShape(10.dp)
          ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Regenerate World Seed", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}


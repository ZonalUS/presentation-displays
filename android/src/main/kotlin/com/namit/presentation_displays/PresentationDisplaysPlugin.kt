// android/src/main/kotlin/com/namit/presentation_displays/PresentationDisplaysPlugin.kt
package com.namit.presentation_displays

import android.content.ContentValues.TAG
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.annotation.NonNull
import com.google.gson.Gson
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/** PresentationDisplaysPlugin (Flutter v2 embedding) */
class PresentationDisplaysPlugin :
	FlutterPlugin,
	ActivityAware,
	MethodChannel.MethodCallHandler {

	private lateinit var channel: MethodChannel
	private lateinit var eventChannel: EventChannel
	private var flutterEngineChannel: MethodChannel? = null
	private var context: Context? = null
	private var presentation: PresentationDisplay? = null
	private var flutterBinding: FlutterPlugin.FlutterPluginBinding? = null

	override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
		flutterBinding = flutterPluginBinding
		channel = MethodChannel(flutterPluginBinding.binaryMessenger, VIEW_TYPE_ID)
		channel.setMethodCallHandler(this)

		eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, VIEW_TYPE_EVENTS_ID)
		displayManager = flutterPluginBinding
			.applicationContext
			.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
		eventChannel.setStreamHandler(DisplayConnectedStreamHandler(displayManager))
	}

	override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
		channel.setMethodCallHandler(null)
		eventChannel.setStreamHandler(null)
		flutterEngineChannel = null
		flutterBinding = null
	}

	override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
		Log.i(TAG, "Channel: method: ${call.method} | arguments: ${call.arguments}")
		val channelMainName = "main_display_channel"

		when (call.method) {
			"showPresentation" -> {
				try {
					val obj = JSONObject(call.arguments as String)
					val displayId = obj.getInt("displayId")
					val routeTag = obj.getString("routerName")
					val display = displayManager?.getDisplay(displayId)

					if (display != null) {
						val dataToMainCallback: (Any?) -> Unit = { argument ->
							flutterBinding?.let { binding ->
								MethodChannel(binding.binaryMessenger, channelMainName)
									.invokeMethod("dataToMain", argument)
							}
						}

						val flutterEngine = createFlutterEngine(routeTag)
						if (flutterEngine != null) {
							flutterEngineChannel = MethodChannel(
								flutterEngine.dartExecutor.binaryMessenger,
								"${VIEW_TYPE_ID}_engine"
							)
							presentation = context?.let { ctx ->
								PresentationDisplay(ctx, routeTag, display, dataToMainCallback)
							}
							Log.i(TAG, "presentation: $presentation")
							presentation?.show()
							result.success(true)
						} else {
							result.error("404", "Can't find FlutterEngine", null)
						}
					} else {
						result.error("404", "Can't find display with displayId=$displayId", null)
					}
				} catch (e: Exception) {
					result.error(call.method, e.message, null)
				}
			}
			"hidePresentation" -> {
				try {
					presentation?.dismiss()
					presentation = null
					result.success(true)
				} catch (e: Exception) {
					result.error(call.method, e.message, null)
				}
			}
			"listDisplay" -> {
				val listJson = ArrayList<DisplayJson>()
				val category = call.arguments as? String
				val displays = displayManager?.getDisplays(category)
				displays?.forEach { d ->
					listJson.add(DisplayJson(d.displayId, d.flags, d.rotation, d.name))
				}
				result.success(Gson().toJson(listJson))
			}
			"transferDataToPresentation" -> {
				try {
					flutterEngineChannel?.invokeMethod("DataTransfer", call.arguments)
					result.success(true)
				} catch (_: Exception) {
					result.success(false)
				}
			}
			else -> result.notImplemented()
		}
	}

	private fun createFlutterEngine(tag: String): FlutterEngine? {
		val ctx = context ?: return null

		if (FlutterEngineCache.getInstance().get(tag) == null) {
			val flutterEngine = FlutterEngine(ctx)
			flutterEngine.navigationChannel.setInitialRoute(tag)

			FlutterInjector.instance().flutterLoader().startInitialization(ctx)
			val path = FlutterInjector.instance().flutterLoader().findAppBundlePath()
			val entrypoint = DartExecutor.DartEntrypoint(path, "secondaryDisplayMain")
			flutterEngine.dartExecutor.executeDartEntrypoint(entrypoint)
			flutterEngine.lifecycleChannel.appIsResumed()

			FlutterEngineCache.getInstance().put(tag, flutterEngine)
		}
		return FlutterEngineCache.getInstance().get(tag)
	}

	/* ActivityAware */
	override fun onAttachedToActivity(binding: ActivityPluginBinding) {
		context = binding.activity
		displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
	}
	override fun onDetachedFromActivityForConfigChanges() { context = null }
	override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
		context = binding.activity
		displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
	}
	override fun onDetachedFromActivity() { context = null }

	companion object {
		private const val VIEW_TYPE_ID = "presentation_displays_plugin"
		private const val VIEW_TYPE_EVENTS_ID = "presentation_displays_plugin_events"
		private var displayManager: DisplayManager? = null
	}
}

/* Stream handler */
class DisplayConnectedStreamHandler(private var displayManager: DisplayManager?) :
	EventChannel.StreamHandler {
	private var sink: EventChannel.EventSink? = null
	private var handler: Handler? = null

	private val displayListener = object : DisplayManager.DisplayListener {
		override fun onDisplayAdded(displayId: Int)  { sink?.success(1) }
		override fun onDisplayRemoved(displayId: Int){ sink?.success(0) }
		override fun onDisplayChanged(p0: Int) {}
	}

	override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
		sink = events
		handler = Handler(Looper.getMainLooper())
		displayManager?.registerDisplayListener(displayListener, handler)
	}

	override fun onCancel(arguments: Any?) {
		sink = null
		handler = null
		displayManager?.unregisterDisplayListener(displayListener)
	}
}

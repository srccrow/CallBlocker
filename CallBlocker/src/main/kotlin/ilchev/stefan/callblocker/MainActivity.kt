package ilchev.stefan.callblocker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {

	private val blockNonContactsListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
		updateContent(R.id.block_non_contacts) { it.isBlockNonContacts = isChecked }
	}

	private val excludeContactsListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
		updateContent(R.id.exclude_contacts) { it.isExcludeContacts = isChecked }
	}

	private val regexListener = object : TextWatcher {

		override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

		override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

		override fun afterTextChanged(s: Editable) {
			val regex = "$s"
			updateContent(R.id.regex) { it.regex = regex }
		}
	}

	private val blockListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
		val isMatches = checkedId.toBoolean()
		updateContent(R.id.block) { it.isMatches = isMatches }
	}

	private val screenerListener = CompoundButton.OnCheckedChangeListener { buttonView, _ ->
		updateScreener(buttonView)
	}

	private val isStopIntent
		get() = intent?.action == null

	private var isInstallPackagesRequester
		get() = packageManager.canRequestPackageInstalls() ||
				getSharedPreferences(BuildConfig.APPLICATION_ID, MODE_PRIVATE)
					.getBoolean(Manifest.permission.REQUEST_INSTALL_PACKAGES, false)
		set(value) = getSharedPreferences(BuildConfig.APPLICATION_ID, MODE_PRIVATE)
			.edit()
			.putBoolean(Manifest.permission.REQUEST_INSTALL_PACKAGES, value)
			.apply()

	private fun isActivityFound(
		intent: Intent
	) = try {
		val activityInfo = intent.resolveActivityInfo(packageManager, 0)
		activityInfo?.isEnabled == true && activityInfo.exported
	} catch (t: Throwable) {
		Log.w(TAG, t)
		false
	}

	private fun tryStartActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
		try {
			startActivityForResult(intent, requestCode, options)
		} catch (t: Throwable) {
			Log.w(TAG, t)
		}
	}

	private fun tryStopService(
		intent: Intent
	) = try {
		stopService(intent)
	} catch (t: Throwable) {
		Log.w(TAG, t)
		false
	}

	private fun <T : View> findViewById(
		sourceId: Int,
		id: Int
	) = if (sourceId != id) findViewById<T>(id) else null

	private fun updateContent(sourceId: Int, consumer: ((BlockPredicate) -> Unit)?) {
		val sharedPreferences = getSharedPreferences(BuildConfig.APPLICATION_ID, MODE_PRIVATE)
		var error: String? = null
		try {
			val blockPredicate = BlockPredicate(sharedPreferences)
			if (consumer != null) {
				consumer(blockPredicate)
				blockPredicate.put(sharedPreferences.edit()).apply()
			}
			findViewById<CompoundButton>(sourceId, R.id.block_non_contacts)?.apply {
				setChecked(blockPredicate.isBlockNonContacts, blockNonContactsListener)
			}
			findViewById<CompoundButton>(sourceId, R.id.exclude_contacts)?.apply {
				setChecked(blockPredicate.isExcludeContacts, excludeContactsListener)
			}
			findViewById<TextView>(sourceId, R.id.regex)?.apply {
				removeTextChangedListener(regexListener)
				text = blockPredicate.regex
				addTextChangedListener(regexListener)
			}
			findViewById<RadioGroup>(sourceId, R.id.block)?.apply {
				setOnCheckedChangeListener(null)
				check(blockPredicate.isMatches.toBlockId())
				setOnCheckedChangeListener(blockListener)
			}
		} catch (t: Throwable) {
			error = t.localizedMessage
		} finally {
			findViewById<TextView>(R.id.error)?.text = error
		}
	}

	private fun updateScreener(buttonView: CompoundButton?, screenerVisibility: Int, isScreener: Boolean) {
		(buttonView ?: findViewById(R.id.screener))?.apply {
			visibility = screenerVisibility
			setChecked(isScreener, screenerListener)
		}
	}

	private fun updateScreener(buttonView: CompoundButton?) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			updateScreener(buttonView, View.GONE, false)
			return
		}
		val roleManager = getSystemService(RoleManager::class.java)
		if (roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) != true) {
			updateScreener(buttonView, View.GONE, false)
			return
		}
		val isScreener = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
		updateScreener(buttonView, View.VISIBLE, isScreener)
		buttonView ?: return
		val intent = if (isScreener) {
			Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
		} else {
			roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
		}
		if (isActivityFound(intent)) {
			tryStartActivityForResult(intent, 0, null)
		}
	}

	private fun request(action: String, requestCode: RequestCode): Boolean {
		val intent = Intent(action, Uri.fromParts("package", packageName, null))
		if (isActivityFound(intent) ||
			isActivityFound(intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
		) {
			tryStartActivityForResult(intent, requestCode.ordinal, null)
			return true
		}
		return false
	}

	private fun requestRequestedPermissions(): Array<String>? {
		try {
			val packageInfo = getPackageInfo(PackageManager.GET_PERMISSIONS)
			val set = mutableSetOf(*packageInfo.requestedPermissions ?: emptyArray())
			if (set.remove(Manifest.permission.REQUEST_INSTALL_PACKAGES) &&
				BuildConfig.LATEST_RELEASE_URL.isNotEmpty() &&
				!isInstallPackagesRequester &&
				request(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, RequestCode.REQUEST_INSTALL_PACKAGES)
			) {
				tryStopService(Intent(this, UpdateService::class.java))
				return arrayOf(Manifest.permission.REQUEST_INSTALL_PACKAGES)
			}
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
				set.remove(UPDATE_PACKAGES_WITHOUT_USER_ACTION)
			}
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
				set.remove(POST_NOTIFICATIONS)
			}
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				set.remove(FOREGROUND_SERVICE_DATA_SYNC)
			}
			if (set.isNotEmpty()) {
				val permissions = set.toTypedArray()
				requestPermissions(permissions, RequestCode.REQUESTED_PERMISSIONS.ordinal)
				return permissions
			}
		} catch (t: Throwable) {
			Log.w(TAG, t)
		}
		return null
	}

	private fun callUpdateService() {
		try {
			val service = (intent?.let(::Intent) ?: Intent()).setClass(this, UpdateService::class.java)
			if (isStopIntent) {
				tryStopService(service)
				finish()
			} else if (BuildConfig.LATEST_RELEASE_URL.isNotEmpty() && packageManager.canRequestPackageInstalls()) {
				startForegroundService(service)
			}
		} catch (t: Throwable) {
			Log.w(TAG, t)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		updateContent(View.NO_ID, null)
		if (requestRequestedPermissions() == null) {
			callUpdateService()
		}
		notifyBlockedCall(null)
	}

	override fun onResume() {
		super.onResume()
		updateScreener(null)
		if (isStopIntent) {
			callUpdateService()
		}
	}

	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<String>,
		grantResults: IntArray
	) = when (requestCode) {
		RequestCode.REQUESTED_PERMISSIONS.ordinal -> callUpdateService()
		else -> Unit
	}

	override fun onActivityResult(
		requestCode: Int,
		resultCode: Int,
		data: Intent?
	) = when (requestCode) {
		RequestCode.REQUEST_INSTALL_PACKAGES.ordinal -> {
			isInstallPackagesRequester = !isInstallPackagesRequester
			if (requestRequestedPermissions() == null) {
				callUpdateService()
			}
			Unit
		}

		else -> Unit
	}

	override fun onNewIntent(intent: Intent) {
		this.intent = intent
	}

	private enum class RequestCode {
		REQUESTED_PERMISSIONS,
		REQUEST_INSTALL_PACKAGES
	}

	companion object {

		private const val TAG = "MainActivity"

		@SuppressLint("InlinedApi")
		private const val UPDATE_PACKAGES_WITHOUT_USER_ACTION = Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION

		@SuppressLint("InlinedApi")
		private const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

		@SuppressLint("InlinedApi")
		private const val FOREGROUND_SERVICE_DATA_SYNC = Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC

		private fun Boolean?.toBlockId() = when (this) {
			null -> R.id.block_none
			true -> R.id.block_matches
			else -> R.id.block_others
		}

		private fun Int.toBoolean() = when (this) {
			R.id.block_matches -> true
			R.id.block_others -> false
			else -> null
		}

		private fun CompoundButton.setChecked(value: Boolean, listener: CompoundButton.OnCheckedChangeListener) {
			setOnCheckedChangeListener(null)
			isChecked = value
			setOnCheckedChangeListener(listener)
		}
	}
}

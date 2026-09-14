package io.github.s1ddhants1.swiftbackupprem.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep
import androidx.recyclerview.widget.RecyclerView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.GoogleServicesJson
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONObject
import java.lang.reflect.Field

@Keep
object InAppSettingsHook : HookHandler {

    const val SBP_CATEGORY_ID = 999
    const val PREF_KEY_SBP = "swiftbackupprem_settings"
    const val PREF_KEY_IMPORT_JSON = "pref_import_google_services"
    const val REQUEST_CODE_PICK_JSON = 48701

    var activePrefs: PreferencesManager? = null
    var activeImportPref: Any? = null

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        activePrefs = prefs

        val prefClass = classLoader.loadClass("androidx.preference.Preference")

        attempt("hook Settings fragment pa7.l", silent = true) {
            val pa7Class = try {
                classLoader.loadClass("pa7")
            } catch (_: ClassNotFoundException) {
                classLoader.loadClass("defpackage.pa7")
            }
            val lMethod = pa7Class.getDeclaredMethod("l")

            module.hookTracked(
                lMethod,
                idPrefix = "sbp-settings-pa7-l",
                priority = XposedInterface.PRIORITY_DEFAULT,
                deoptimize = true
            ).intercept { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject
                if (fragment != null) {
                    attempt("inject SBP entry from pa7.l") {
                        val screen = getPreferenceScreen(fragment)
                        val ctx = attempt("get requireContext", silent = true) {
                            fragment.javaClass.getMethod("requireContext").invoke(fragment) as? Context
                        }
                        if (screen != null && ctx != null) {
                            val helper = HostPreferenceHelper(classLoader, ctx)
                            helper.injectSettingsEntry(screen, ctx, fragment)
                        }
                    }
                }
                result
            }
        }

        attempt("hook pa7.d for click interception") {
            val pa7Class = try {
                classLoader.loadClass("pa7")
            } catch (_: ClassNotFoundException) {
                classLoader.loadClass("defpackage.pa7")
            }
            val dMethod = pa7Class.getDeclaredMethod("d", prefClass)

            module.hookTracked(
                dMethod,
                idPrefix = "sbp-settings-pa7-d",
                priority = XposedInterface.PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                val pref = chain.args[0]
                val key = findFieldInHierarchy(pref.javaClass, "t")?.get(pref) as? String
                if (key == PREF_KEY_SBP) {
                    val fragment = chain.thisObject
                    val act = attempt("get act", silent = true) {
                        fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                    } ?: (fragment.javaClass.getMethod("requireContext").invoke(fragment) as Context)
                    Log.i(Consts.TAG, "SwiftBackupPrem preference clicked via pa7.d! Opening SettingsDetailActivity category=$SBP_CATEGORY_ID")
                    val detailCls = classLoader.loadClass("org.swiftapps.swiftbackup.settings.SettingsDetailActivity")
                    val intent = Intent(act, detailCls).apply {
                        putExtra("category", SBP_CATEGORY_ID)
                        putExtra("category_title", "SwiftBackupPrem")
                    }
                    act.startActivity(intent)
                    return@intercept true
                }
                chain.proceed()
            }
        }

        attempt("hook SettingsDetail fragment a.l", silent = true) {
            val aClass = classLoader.loadClass("org.swiftapps.swiftbackup.settings.a")
            val lMethod = aClass.getDeclaredMethod("l")

            module.hookTracked(
                lMethod,
                idPrefix = "sbp-settings-a-l",
                priority = XposedInterface.PRIORITY_DEFAULT,
                deoptimize = true
            ).intercept { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject
                if (fragment != null) {
                    attempt("populate SBP native settings page from a.l") {
                        val activity = fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                        val category = activity?.intent?.getIntExtra("category", 0) ?: 0
                        Log.i(Consts.TAG, "a.l intercepted! category=$category, activity=$activity")
                        if (activity != null && category == SBP_CATEGORY_ID) {
                            val screen = getPreferenceScreen(fragment)
                            Log.i(Consts.TAG, "a.l PreferenceScreen: $screen")
                            if (screen != null) {
                                val helper = HostPreferenceHelper(classLoader, activity)
                                helper.clearScreen(screen)
                                helper.populateSbpSettings(
                                    screen,
                                    activity,
                                    fragment,
                                    activePrefs ?: PreferencesManager().also { it.loadFromFallbackStorage(activity) }
                                )
                                val cField = findFieldInHierarchy(fragment.javaClass, "c")
                                val rv = cField?.get(fragment) as? RecyclerView
                                rv?.itemAnimator = null
                                refreshRecyclerAdapter(fragment)
                            }
                        }
                    }
                }
                result
            }
        }

        attempt("hook SettingsDetail fragment a.d for SBP item clicks") {
            val aClass = classLoader.loadClass("org.swiftapps.swiftbackup.settings.a")
            val dMethod = aClass.getDeclaredMethod("d", prefClass)

            module.hookTracked(
                dMethod,
                idPrefix = "sbp-settings-a-d",
                priority = XposedInterface.PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                val fragment = chain.thisObject
                val activity = fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                val category = activity?.intent?.getIntExtra("category", 0) ?: 0
                if (activity != null && category == SBP_CATEGORY_ID) {
                    val pref = chain.args[0]
                    val key = findFieldInHierarchy(pref.javaClass, "t")?.get(pref) as? String
                    val handled = handleSbpClick(pref, key, activity)
                    if (handled) return@intercept true
                }
                chain.proceed()
            }
        }

        attempt("hook fm0.onViewCreated to disable ItemAnimator", silent = true) {
            val fm0Class = try {
                classLoader.loadClass("fm0")
            } catch (_: ClassNotFoundException) {
                classLoader.loadClass("defpackage.fm0")
            }
            val onViewCreatedMethod = fm0Class.getMethod("onViewCreated", android.view.View::class.java, Bundle::class.java)

            module.hookTracked(
                onViewCreatedMethod,
                idPrefix = "sbp-fm0-onViewCreated",
                priority = XposedInterface.PRIORITY_DEFAULT,
                deoptimize = true
            ).intercept { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject
                if (fragment != null) {
                    attempt("disable item animator on fragment RecyclerView", silent = true) {
                        val cField = findFieldInHierarchy(fragment.javaClass, "c")
                        val rv = cField?.get(fragment) as? RecyclerView
                        rv?.itemAnimator = null
                        Log.i(Consts.TAG, "Disabled ItemAnimator on settings RecyclerView")
                    }
                }
                result
            }
        }

        attempt("hook RecyclerView.onLayout to prevent animation NPE crash", silent = true) {
            val rvClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val onLayoutMethod = rvClass.getDeclaredMethod(
                "onLayout",
                java.lang.Boolean.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE
            )
            module.hookTracked(
                onLayoutMethod,
                idPrefix = "sbp-rv-onLayout-safe",
                priority = XposedInterface.PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                try {
                    chain.proceed()
                } catch (e: NullPointerException) {
                    Log.w(Consts.TAG, "Safely intercepted RecyclerView onLayout NPE: ${e.message}")
                }
            }
        }

        attempt("hook RecyclerView.setAdapter to disable itemAnimator", silent = true) {
            val rvClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val hg6Class = try {
                classLoader.loadClass("hg6")
            } catch (_: ClassNotFoundException) {
                classLoader.loadClass("defpackage.hg6")
            }
            val setAdapterMethod = rvClass.getDeclaredMethod("setAdapter", hg6Class)

            module.hookTracked(
                setAdapterMethod,
                idPrefix = "sbp-rv-setAdapter",
                priority = XposedInterface.PRIORITY_DEFAULT,
                deoptimize = true
            ).intercept { chain ->
                val rv = chain.thisObject as? RecyclerView
                val adapter = chain.args[0]
                val result = chain.proceed()
                if (rv != null && adapter != null) {
                    val adapterName = adapter.javaClass.name
                    if (adapterName.contains("lt7") || adapterName.contains("f46")) {
                        rv.itemAnimator = null
                        Log.i(Consts.TAG, "Disabled itemAnimator in setAdapter for $adapterName")
                    }
                }
                result
            }
        }

        attempt("hook onActivityResult for JSON picker", silent = true) {
            val detailCls = try {
                classLoader.loadClass("org.swiftapps.swiftbackup.settings.SettingsDetailActivity")
            } catch (_: Throwable) {
                null
            }
            val targetClasses = mutableListOf<Class<*>>()
            var c: Class<*>? = detailCls
            while (c != null && c != Any::class.java) {
                try {
                    c.getDeclaredMethod(
                        "onActivityResult",
                        java.lang.Integer.TYPE,
                        java.lang.Integer.TYPE,
                        Intent::class.java
                    )
                    targetClasses.add(c)
                } catch (_: NoSuchMethodException) {}
                c = c.superclass
            }
            if (targetClasses.isEmpty()) {
                targetClasses.add(classLoader.loadClass("android.app.Activity"))
            }

            for (cls in targetClasses) {
                try {
                    val m = cls.getDeclaredMethod(
                        "onActivityResult",
                        java.lang.Integer.TYPE,
                        java.lang.Integer.TYPE,
                        Intent::class.java
                    )
                    module.hookTracked(
                        m,
                        idPrefix = "sbp-on-result-${cls.simpleName}",
                        priority = XposedInterface.PRIORITY_HIGHEST,
                        deoptimize = true
                    ).intercept { chain ->
                        val reqCode = chain.args[0] as? Int ?: 0
                        val resCode = chain.args[1] as? Int ?: 0
                        val data = chain.args[2] as? Intent
                        if (reqCode == REQUEST_CODE_PICK_JSON && resCode == Activity.RESULT_OK && data?.data != null) {
                            val act = chain.thisObject as? Activity
                            val uri = data.data
                            if (act != null && uri != null) {
                                handleImportedUri(act, uri)
                                return@intercept null
                            }
                        }
                        chain.proceed()
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun handleSbpClick(pref: Any, key: String?, activity: Activity): Boolean {
        val p = activePrefs ?: PreferencesManager().also { it.loadFromFallbackStorage(activity) }
        activePrefs = p

        val f0Field = findFieldInHierarchy(pref.javaClass, "f0")
        if (f0Field != null && key != null) {
            val newChecked = f0Field.get(pref) as? Boolean ?: false

            when (key) {
                "enable_premium" -> p.enablePremium = newChecked
                "disable_telemetry" -> p.disableTelemetry = newChecked
                "unlock_local_cloud_features" -> p.unlockLocalCloudFeatures = newChecked
                "custom_firebase_app" -> p.customFirebaseApp = newChecked
            }
            p.saveToFallbackStorage(activity)
            Log.i(Consts.TAG, "Toggled $key -> $newChecked and saved")
            return true
        }

        when (key) {
            PREF_KEY_IMPORT_JSON -> {
                launchJsonFilePicker(activity, pref)
                return true
            }
            "pref_link_telegram" -> {
                attempt("open telegram link", silent = true) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SwiftBackupPrem")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                }
                return true
            }
            "pref_link_github" -> {
                attempt("open github link", silent = true) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/s1ddhants1/SwiftBackupPrem")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                }
                return true
            }
        }

        return false
    }

    private fun launchJsonFilePicker(activity: Activity, pref: Any) {
        activeImportPref = pref
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "*/*"))
        }
        try {
            activity.startActivityForResult(intent, REQUEST_CODE_PICK_JSON)
        } catch (_: Throwable) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "*/*"))
                }
                activity.startActivityForResult(fallbackIntent, REQUEST_CODE_PICK_JSON)
            } catch (e: Throwable) {
                Toast.makeText(activity, "Unable to launch file picker: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleImportedUri(activity: Activity, uri: Uri) {
        attempt("handle imported json uri") {
            val jsonString = activity.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            }
            if (jsonString.isNullOrBlank()) {
                Toast.makeText(activity, "Failed to read file from picker", Toast.LENGTH_SHORT).show()
                return@attempt
            }
            val p = activePrefs ?: PreferencesManager().also { it.loadFromFallbackStorage(activity) }
            activePrefs = p
            applyGoogleServicesJsonString(activity, p, jsonString, activeImportPref)
        }
    }

    private fun applyGoogleServicesJsonString(
        activity: Activity,
        prefs: PreferencesManager,
        jsonString: String,
        targetPref: Any? = null
    ): Boolean {
        return try {
            val rawJson = JSONObject(jsonString)
            val isGoogleServices = rawJson.has("client") && rawJson.has("project_info")
            if (!isGoogleServices) {
                Toast.makeText(activity, "Invalid format: Not a google-services.json file", Toast.LENGTH_LONG).show()
                return false
            }
            GoogleServicesJson.applyToPrefs(rawJson, prefs)
            if (prefs.firebaseDatabaseUrl.isBlank() && prefs.projectId.isNotBlank()) {
                prefs.firebaseDatabaseUrl = "https://${prefs.projectId}-default-rtdb.firebaseio.com"
            }
            prefs.customFirebaseApp = true
            prefs.saveToFallbackStorage(activity)

            val newSummary = "Configured: ${prefs.projectId} (Tap to update)"
            if (targetPref != null) {
                findFieldInHierarchy(targetPref.javaClass, "p")?.set(targetPref, newSummary)
                try {
                    targetPref.javaClass.getMethod("B", CharSequence::class.java).invoke(targetPref, newSummary)
                } catch (_: Throwable) {}
            }
            Toast.makeText(
                activity,
                "Imported Firebase config for project:\n${prefs.projectId}",
                Toast.LENGTH_LONG
            ).show()
            Log.i(Consts.TAG, "Successfully applied google-services.json for project: ${prefs.projectId}")
            true
        } catch (e: Throwable) {
            Log.e(Consts.TAG, "Error importing google-services.json: ${e.message}", e)
            Toast.makeText(activity, "Failed to parse JSON: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun findFieldInHierarchy(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                val f = current.getDeclaredField(fieldName)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun getPreferenceScreen(fragment: Any): Any? {
        return try {
            val bField = findFieldInHierarchy(fragment.javaClass, "b")
            val bVal = bField?.get(fragment)
            if (bVal != null) {
                val gField = findFieldInHierarchy(bVal.javaClass, "g")
                val gVal = gField?.get(bVal)
                if (gVal != null) {
                    return gVal
                }
            }

            var current: Class<*>? = fragment.javaClass
            while (current != null && current != Any::class.java) {
                for (f in current.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(fragment) ?: continue
                    if (v.javaClass.name.endsWith("PreferenceScreen")) return v
                    for (subF in v.javaClass.declaredFields) {
                        subF.isAccessible = true
                        val subV = subF.get(v) ?: continue
                        if (subV.javaClass.name.endsWith("PreferenceScreen")) return subV
                    }
                }
                current = current.superclass
            }
            null
        } catch (e: Throwable) {
            Log.w(Consts.TAG, "Failed to get PreferenceScreen from fragment: ${e.message}", e)
            null
        }
    }

    private fun refreshRecyclerAdapter(fragment: Any) {
        attempt("refresh recycler adapter", silent = true) {
            val cField = findFieldInHierarchy(fragment.javaClass, "c")
            val rv = cField?.get(fragment) as? RecyclerView
            if (rv != null) {
                rv.itemAnimator = null
                val adapter = rv.adapter
                if (adapter != null) {
                    try {
                        val oMethod = adapter.javaClass.getMethod("o")
                        oMethod.isAccessible = true
                        oMethod.invoke(adapter)
                        Log.i(Consts.TAG, "Invoked adapter.o() successfully")
                        return@attempt
                    } catch (e: Throwable) {
                        Log.w(Consts.TAG, "adapter.o() invocation failed: ${e.message}")
                    }
                    adapter.notifyDataSetChanged()
                    return@attempt
                }
            }

            var current: Class<*>? = fragment.javaClass
            while (current != null && current != Any::class.java) {
                for (f in current.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(fragment)
                    if (v is RecyclerView) {
                        v.itemAnimator = null
                        val adapter = v.adapter
                        if (adapter != null) {
                            try {
                                val oMethod = adapter.javaClass.getMethod("o")
                                oMethod.isAccessible = true
                                oMethod.invoke(adapter)
                                Log.i(Consts.TAG, "Invoked adapter.o() successfully from search")
                                return@attempt
                            } catch (_: Throwable) {}
                            adapter.notifyDataSetChanged()
                        }
                        return@attempt
                    }
                }
                current = current.superclass
            }
        }
    }

    class HostPreferenceHelper(val cl: ClassLoader, val ctx: Context) {
        val prefClass: Class<*> = cl.loadClass("androidx.preference.Preference")
        val catClass: Class<*> = cl.loadClass("androidx.preference.PreferenceCategory")
        val prefGroupClass: Class<*> = cl.loadClass("androidx.preference.PreferenceGroup")

        val mSwitchClass: Class<*>? = try {
            cl.loadClass("org.swiftapps.swiftbackup.settings.MSwitchPreference")
        } catch (_: Throwable) {
            try { cl.loadClass("androidx.preference.SwitchPreferenceCompat") } catch (_: Throwable) { null }
        }

        val fm0Class: Class<*>? = try {
            cl.loadClass("fm0")
        } catch (_: Throwable) {
            try { cl.loadClass("defpackage.fm0") } catch (_: Throwable) { null }
        }

        @Suppress("UNCHECKED_CAST")
        fun getGroupList(group: Any): ArrayList<Any>? {
            val f = findFieldInHierarchy(group.javaClass, "g0")
            f?.isAccessible = true
            return f?.get(group) as? ArrayList<Any>
        }

        fun findPreference(group: Any, key: String): Any? {
            try {
                val gMethod = group.javaClass.getMethod("G", CharSequence::class.java)
                val res = gMethod.invoke(group, key)
                if (res != null) return res
            } catch (_: Throwable) {}

            val list = getGroupList(group) ?: return null
            for (item in list) {
                val itemKey = findFieldInHierarchy(item.javaClass, "t")?.get(item) as? String
                if (itemKey == key) return item
                if (prefGroupClass.isInstance(item)) {
                    val sub = findPreference(item, key)
                    if (sub != null) return sub
                }
            }
            return null
        }

        fun addPreference(group: Any, pref: Any) {
            val list = getGroupList(group)
            if (list != null && !list.contains(pref)) {
                findFieldInHierarchy(pref.javaClass, "a0")?.set(pref, group)
                list.add(pref)
            }
        }

        fun bindClickListeners(group: Any, listener: Any) {
            val list = getGroupList(group) ?: return
            for (item in list) {
                if (prefGroupClass.isInstance(item)) {
                    bindClickListeners(item, listener)
                } else {
                    findFieldInHierarchy(item.javaClass, "f")?.set(item, listener)
                }
            }
        }

        fun clearScreen(screen: Any) {
            getGroupList(screen)?.clear()
        }

        fun createCategory(title: String, order: Int = Integer.MAX_VALUE): Any {
            val cat = catClass.getConstructor(Context::class.java).newInstance(ctx)
            findFieldInHierarchy(catClass, "n")?.set(cat, title)
            findFieldInHierarchy(catClass, "J")?.set(cat, false)
            if (order != Integer.MAX_VALUE) {
                findFieldInHierarchy(catClass, "k")?.set(cat, order)
            }
            return cat
        }

        fun createSwitch(
            key: String,
            title: String,
            summary: String,
            initialValue: Boolean,
            order: Int = Integer.MAX_VALUE
        ): Any {
            val switchPref = if (mSwitchClass != null && mSwitchClass.name.endsWith("MSwitchPreference")) {
                try {
                    val ctor = mSwitchClass.getConstructor(Context::class.java, AttributeSet::class.java)
                    ctor.newInstance(ctx, null)
                } catch (_: Throwable) {
                    mSwitchClass.getConstructor(Context::class.java).newInstance(ctx)
                }
            } else if (mSwitchClass != null) {
                mSwitchClass.getConstructor(Context::class.java).newInstance(ctx)
            } else {
                prefClass.getConstructor(Context::class.java).newInstance(ctx)
            }

            findFieldInHierarchy(switchPref.javaClass, "t")?.set(switchPref, key)
            findFieldInHierarchy(switchPref.javaClass, "n")?.set(switchPref, title)
            findFieldInHierarchy(switchPref.javaClass, "p")?.set(switchPref, summary)
            findFieldInHierarchy(switchPref.javaClass, "J")?.set(switchPref, false)
            if (order != Integer.MAX_VALUE) {
                findFieldInHierarchy(switchPref.javaClass, "k")?.set(switchPref, order)
            }

            findFieldInHierarchy(switchPref.javaClass, "f0")?.set(switchPref, initialValue)
            findFieldInHierarchy(switchPref.javaClass, "i0")?.set(switchPref, true)

            return switchPref
        }

        fun createItem(
            key: String,
            title: String,
            summary: String,
            iconId: Int = 0,
            order: Int = Integer.MAX_VALUE
        ): Any {
            val pref = prefClass.getConstructor(Context::class.java).newInstance(ctx)
            findFieldInHierarchy(prefClass, "t")?.set(pref, key)
            findFieldInHierarchy(prefClass, "n")?.set(pref, title)
            findFieldInHierarchy(prefClass, "p")?.set(pref, summary)
            findFieldInHierarchy(prefClass, "J")?.set(pref, false)
            if (order != Integer.MAX_VALUE) {
                findFieldInHierarchy(prefClass, "k")?.set(pref, order)
            }
            if (iconId != 0) {
                findFieldInHierarchy(prefClass, "q")?.set(pref, iconId)
                findFieldInHierarchy(prefClass, "R")?.set(pref, true)
            }
            return pref
        }

        fun applySegmentedStyling(screen: Any) {
            attempt("apply fm0.m segmented styling", silent = true) {
                fm0Class?.getDeclaredMethod("m", prefGroupClass)?.invoke(null, screen)
            }
        }

        fun injectSettingsEntry(screen: Any, context: Context, fragment: Any) {
            if (findPreference(screen, PREF_KEY_SBP) != null) {
                Log.d(Consts.TAG, "SwiftBackupPrem preference already present")
                return
            }

            val res = context.resources
            val pkg = context.packageName

            val segmentedLayout = res.getIdentifier("preference_segmented", "layout", pkg)
            val boltIcon = res.getIdentifier("ic_settings_bolt_filled", "drawable", pkg)

            val sbpPref = createItem(
                PREF_KEY_SBP,
                "SwiftBackupPrem",
                "Module Settings",
                boltIcon
            )

            if (segmentedLayout != 0) {
                findFieldInHierarchy(sbpPref.javaClass, "W")?.set(sbpPref, segmentedLayout)
            }

            try {
                val detailCls = cl.loadClass("org.swiftapps.swiftbackup.settings.SettingsDetailActivity")
                val intent = Intent(context, detailCls).apply {
                    putExtra("category", SBP_CATEGORY_ID)
                    putExtra("category_title", "SwiftBackupPrem")
                }
                findFieldInHierarchy(sbpPref.javaClass, "x")?.set(sbpPref, intent)
            } catch (t: Throwable) {
                Log.w(Consts.TAG, "Failed to set intent on sbpPref: ${t.message}")
            }

            findFieldInHierarchy(sbpPref.javaClass, "f")?.set(sbpPref, fragment)

            var targetGroup: Any? = null
            val list = getGroupList(screen)
            if (list != null) {
                for (p in list) {
                    if (prefGroupClass.isInstance(p)) {
                        if (findPreference(p, "labs") != null ||
                            findPreference(p, "restart_app") != null ||
                            findPreference(p, "swiftlogger") != null
                        ) {
                            targetGroup = p
                            break
                        }
                    }
                }
            }

            if (targetGroup != null) {
                addPreference(targetGroup, sbpPref)
                Log.i(Consts.TAG, "Successfully injected SwiftBackupPrem preference into Advanced tools category!")
            } else {
                addPreference(screen, sbpPref)
                Log.i(Consts.TAG, "Successfully injected SwiftBackupPrem preference into root screen!")
            }
        }

        fun populateSbpSettings(
            screen: Any,
            activity: Activity,
            fragment: Any,
            prefs: PreferencesManager
        ) {
            val res = ctx.resources
            val pkg = ctx.packageName

            var idCounter = 100000L
            val prefManager = findFieldInHierarchy(screen.javaClass, "b")?.get(screen)
                ?: findFieldInHierarchy(fragment.javaClass, "b")?.get(fragment)

            fun initItem(item: Any) {
                if (prefManager != null) {
                    findFieldInHierarchy(item.javaClass, "b")?.set(item, prefManager)
                }
                findFieldInHierarchy(item.javaClass, "c")?.set(item, idCounter++)
            }

            fun <T : Any> add(parent: Any, item: T): T {
                initItem(item)
                addPreference(parent, item)
                return item
            }

            val catPremium = add(screen, createCategory("Premium & System", 100))

            add(
                catPremium,
                createSwitch(
                    "enable_premium",
                    "Unlock Premium",
                    "Enables Premium Features",
                    prefs.enablePremium,
                    101
                )
            )

            add(
                catPremium,
                createSwitch(
                    "disable_telemetry",
                    "Disable Telemetry",
                    "Block analytics, crashlytics, and tracking pings",
                    prefs.disableTelemetry,
                    102
                )
            )

            add(
                catPremium,
                createSwitch(
                    "unlock_local_cloud_features",
                    "Unlock Local Cloud Providers",
                    "Enable Cloud Backups without Firebase",
                    prefs.unlockLocalCloudFeatures,
                    103
                )
            )

            val catCloud = add(screen, createCategory("Cloud Integrations", 200))

            add(
                catCloud,
                createSwitch(
                    "custom_firebase_app",
                    "Custom Firebase App",
                    "Use self-hosted Firebase project for cloud features",
                    prefs.customFirebaseApp,
                    201
                )
            )

            val uploadIcon = res.getIdentifier("ic_settings_upload_file_filled", "drawable", pkg)
            val pId = prefs.projectId
            val importSummary = if (pId.isNotBlank()) {
                "Configured: $pId (Tap to update)"
            } else {
                "Import credentials from google-services.json"
            }

            val importItem = createItem(
                PREF_KEY_IMPORT_JSON,
                "Import google-services.json",
                importSummary,
                uploadIcon,
                202
            )
            add(catCloud, importItem)

            val catLinks = add(screen, createCategory("Links", 300))

            val telegramIcon = res.getIdentifier("ic_telegram", "drawable", pkg)
            val linkIcon = res.getIdentifier("ic_link_outline", "drawable", pkg)

            val telegramItem = createItem(
                "pref_link_telegram",
                "Telegram Group",
                "Join chat, release updates, and support",
                telegramIcon,
                301
            )
            val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SwiftBackupPrem")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            findFieldInHierarchy(telegramItem.javaClass, "x")?.set(telegramItem, telegramIntent)
            add(catLinks, telegramItem)

            val githubItem = createItem(
                "pref_link_github",
                "GitHub",
                "Source code, releases, and issue tracker",
                linkIcon,
                302
            )
            val githubIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/s1ddhants1/SwiftBackupPrem")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            findFieldInHierarchy(githubItem.javaClass, "x")?.set(githubItem, githubIntent)
            add(catLinks, githubItem)

            bindClickListeners(screen, fragment)

            applySegmentedStyling(screen)

            Log.i(Consts.TAG, "Rendered native SwiftBackupPrem settings screen in SettingsDetailActivity")
        }
    }
}

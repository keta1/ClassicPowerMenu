package com.kieronquinn.app.classicpowermenu.components.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import androidx.core.os.BuildCompat
import com.kieronquinn.app.classicpowermenu.BuildConfig
import com.kieronquinn.app.classicpowermenu.IGlobalActions
import com.kieronquinn.app.classicpowermenu.service.globalactions.GlobalActionsService
import com.kieronquinn.app.classicpowermenu.utils.extensions.SystemProperties_getString
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

class Xposed: IXposedHookLoadPackage, ServiceConnection {

    companion object {
        private const val TAG = "CPMXposed"
    }

    private val serviceIntent by lazy {
        Intent().apply {
            `package` = BuildConfig.APPLICATION_ID
            component = ComponentName(BuildConfig.APPLICATION_ID, GlobalActionsService::class.java.name)
        }
    }

    private var service: IGlobalActions? = null

    private var isHooked = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if(lpparam.packageName == "com.android.systemui") hookSystemUI(lpparam)
        if(lpparam.packageName == BuildConfig.APPLICATION_ID) hookSelf(lpparam)
    }

    /**
     *  Self-hooks [XposedSelfHook.isXposedHooked] to return true
     */
    private fun hookSelf(lpparam: XC_LoadPackage.LoadPackageParam){
        XposedHelpers.findAndHookMethod(XposedSelfHook::class.java.name, lpparam.classLoader, "isXposedHooked", object: XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any {
                param.result = true
                return true
            }
        })
    }

    private fun hookSystemUI(lpparam: XC_LoadPackage.LoadPackageParam){
        val miuiVersion = SystemProperties_getString("ro.miui.ui.version.name", "V0")
            .substring(1).toIntOrNull() ?: -1
        when {
            miuiVersion >= 125 -> hookMiuiSystemUI(lpparam)
            else -> hookAospSystemUI(lpparam)
        }
    }

    private fun hookAospSystemUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        val globalActionsDialogClassName = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            "com.android.systemui.globalactions.GlobalActionsDialogLite"
        }else{
            "com.android.systemui.globalactions.GlobalActionsDialog"
        }

        val globalActionsDialogClass = XposedHelpers.findClass(globalActionsDialogClassName, lpparam.classLoader)

        //Bind the service when the dialog starts for the best chance of it being ready
        XposedBridge.hookMethod(globalActionsDialogClass.constructors[0], object: XC_MethodHook(){
            override fun afterHookedMethod(param: MethodHookParam) {
                tryBindService(param.args[0] as Context)
            }
        })

        //Find the showOrHideDialog method, which has multiple variants but always the same first 2 args
        val showMethod = globalActionsDialogClass.declaredMethods
            .firstOrNull { it.name == "showOrHideDialog" } ?: return

        val hideMethod = globalActionsDialogClass
            .getDeclaredMethod("dismissGlobalActionsMenu")

        val mKeyguardShowing = globalActionsDialogClass
            .getDeclaredField("mKeyguardShowing").apply {
                isAccessible = true
            }

        val mDeviceProvisioned = globalActionsDialogClass
            .getDeclaredField("mDeviceProvisioned").apply {
                isAccessible = true
            }

        XposedBridge.hookMethod(showMethod, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val keyguardShowing = param.args[0]
                val deviceProvisioned = param.args[1]
                mKeyguardShowing.set(param.thisObject, keyguardShowing)
                mDeviceProvisioned.set(param.thisObject, deviceProvisioned)
                if(handleShow(param)){
                    param.result = true
                }
            }
        })

        XposedBridge.hookMethod(hideMethod, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                handleDismiss()
            }
        })
    }

    private fun hookMiuiSystemUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pluginManagerImplClass = XposedHelpers.findClass("com.android.systemui.shared.plugins.PluginManagerImpl", lpparam.classLoader)
        val m = pluginManagerImplClass.getDeclaredMethod("getClassLoader", ApplicationInfo::class.java)
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                kotlin.runCatching {
                    val applicationInfo = param.args[0] as ApplicationInfo
                    val classLoader = param.result as? ClassLoader ?: return
                    if (applicationInfo.packageName != "miui.systemui.plugin" || isHooked) return

                    val globalActionsDialogClass = XposedHelpers.findClass("com.android.systemui.miui.globalactions.MiuiGlobalActionsDialog", classLoader)
                    //Bind the service when the dialog starts for the best chance of it being ready
                    XposedBridge.hookMethod(globalActionsDialogClass.constructors[0], object: XC_MethodHook(){
                        override fun afterHookedMethod(param: MethodHookParam) {
                            tryBindService(param.args[0] as Context)
                        }
                    })

                    //Calls the service if connected, and prevents the normal dialog showing if that returns true
                    XposedHelpers.findAndHookMethod(globalActionsDialogClass, "showDialog", object: XC_MethodHook(){
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if(handleShow(param)){
                                param.result = null
                            }
                        }
                    })

                    //Optionally calls to the service, if it exists
                    XposedHelpers.findAndHookMethod(globalActionsDialogClass, "dismiss", Int::class.java, object: XC_MethodHook(){
                        override fun beforeHookedMethod(param: MethodHookParam?) {
                            handleDismiss()
                        }
                    })
                    isHooked = true
                }.onFailure {
                    Log.d(TAG, "Failed to hookMiuiSystemUI", it)
                    XposedBridge.log(it)
                }
            }
        })
    }

    private fun showGlobalActions(service: IGlobalActions): Boolean {
        return try {
            service.showGlobalActions()
        }catch (e: Exception){
            false
        }
    }

    private fun handleShow(param: XC_MethodHook.MethodHookParam): Boolean {
        return service?.let {
            if(showGlobalActions(it)){
                //We need to call onGlobalActionsShown to prevent the legacy dialog showing
                sendOnGlobalActionsShown(param)
                return@let true
            } else false
        } ?: run {
            //Bind service for next time
            val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
            tryBindService(context)
            return@run false
        }
    }

    private fun handleDismiss(){
        service?.hideGlobalActions()
    }

    private fun sendOnGlobalActionsShown(param: XC_MethodHook.MethodHookParam){
        val mWindowManagerFuncs = XposedHelpers.getObjectField(param.thisObject, "mWindowManagerFuncs")
        XposedHelpers.callMethod(mWindowManagerFuncs, "onGlobalActionsShown")
    }

    private fun tryBindService(context: Context) {
        try {
            context.bindService(serviceIntent, this, Context.BIND_AUTO_CREATE)
        }catch (e: Exception){
            Log.d(TAG, "Failed to bind to GlobalActionsDialog service", e)
            XposedBridge.log(e)
        }
    }

    override fun onServiceConnected(component: ComponentName, binder: IBinder) {
        service = IGlobalActions.Stub.asInterface(binder)
    }

    override fun onServiceDisconnected(component: ComponentName) {
        service = null
    }

}
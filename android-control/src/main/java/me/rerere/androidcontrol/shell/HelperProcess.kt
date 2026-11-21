// Adapted from Mika (wangzan101/Mika), revision 688f59d5a18d99d0231504598da4059f3feb226e. Apache-2.0.
package me.rerere.androidcontrol.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import me.rerere.androidcontrol.shell.HelperHandoff
import java.io.File
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/**
 * 内置助手进程的入口。宿主应用 经本机无线调试(adbd 的 exec: 服务)以 shell 身份启动它:
 * `CLASSPATH=<base.apk> app_process /system/bin --nice-name=<包名>:helper me.rerere.androidcontrol.shell.HelperMain <包名> <宿主应用 的 uid> <签名指纹>`,
 * 一次性口令从标准输入读一行(见 [HelperHandoff.command])。电脑经 USB 调试跑「用电脑启动」脚本时起的也是它,命令和参数相同
 * (见 [HelperHandoff.usbScript])。
 *
 * 特权逻辑全在 [ShellService];这里只负责把它的 Binder 交给 宿主应用,并且在 宿主应用 进程结束后不退出:
 * 撤掉 宿主应用 留下的改动,等 宿主应用 再起来时重新交接。
 */
object HelperProcess {
    private const val TAG = "AndroidControlHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            Log.e(TAG, "usage: HelperMain <package> <uid> <cert-sha256> (nonce on stdin)")
            exitProcess(2)
        }
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        val certPin = args[2]
        // 读完就关:标准输入只用来递口令
        val launchNonce = runCatching { System.`in`.bufferedReader().readLine()?.trim() }.getOrNull()
        runCatching { System.`in`.close() }
        if (launchNonce.isNullOrEmpty()) {
            Log.e(TAG, "no nonce on stdin")
            exitProcess(2)
        }
        // ShellContext 自建 ActivityThread 时要有主线程 Looper
        Looper.prepareMainLooper()
        check(Process.myUid() == 2000) { "Helper must run as shell UID 2000" }
        val service = ShellService(allowedUid = appUid)
        val helper = Helper(service, packageName, appUid, certPin)
        helper.start(launchNonce)
        Looper.loop()
    }

    private class Helper(
        private val service: ShellService,
        private val packageName: String,
        private val appUid: Int,
        private val certPin: String,
    ) {
        private val context: Context = ShellContext.create()
        private val authority = HelperHandoff.authority(packageName)
        private val userId = appUid / PER_USER_RANGE
        /** 交接、宿主应用 死亡处理都排在这一条线程上,彼此不交错,也不堵主线程。 */
        private val worker = Handler(HandlerThread("helper-handoff").apply { start() }.looper)
        /** 下次交接用的口令(宿主应用 上次收下时换发的)。 */
        private var nonce: String? = null
        /** 宿主应用 进程交给我们的令牌;为 null 表示 宿主应用 现在不在。 */
        private var appToken: IBinder? = null
        private val activityManager by lazy {
            ActivityManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
                .apply { isAccessible = true }.newInstance(context, worker)
        }
        private var recoveryPending = false
        private val recovery = Runnable {
            recoveryPending = false
            if (appToken == null && nonce != null) {
                if (!signedAsPinned()) {
                    cancelRecovery()
                    runCatching { service.releaseAll() }
                    exitProcess(0)
                }
                // Looking at an existing UID never starts its App. In particular, do not acquire
                // a provider for a force-stopped App merely because this helper is still alive.
                if (mayResumeExistingApp()) {
                    when (deliver(nonce, resumeOnly = true)) {
                        Outcome.Accepted -> cancelRecovery()
                        Outcome.Rejected -> { cancelRecovery(); exitProcess(0) }
                        Outcome.Failed -> scheduleRecovery()
                    }
                } else scheduleRecovery()
            }
        }

        private fun scheduleRecovery() {
            if (appToken != null || nonce == null || recoveryPending) return
            recoveryPending = true
            worker.postDelayed(recovery, RECOVERY_POLL_MS)
        }

        private fun cancelRecovery() {
            worker.removeCallbacks(recovery)
            recoveryPending = false
        }

        fun start(launchNonce: String) = worker.post {
            // A package update or instrumentation can replace the provider while its Binder call
            // is in flight. Retry transient transport death, never an authentication rejection.
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (true) {
                when (deliver(launchNonce)) {
                    // Hub revokes the previous authenticated Binder. Do not kill same-name
                    // shell processes: another Android user's app can share the package name.
                    Outcome.Accepted -> { watchUid(); break }
                    Outcome.Rejected -> exitProcess(0)
                    Outcome.Failed -> {
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) exitProcess(1)
                        android.os.SystemClock.sleep(250)
                    }
                }
            }
        }

        private enum class Outcome { Accepted, Rejected, Failed }

        /** 经 ActivityManager 拿 宿主应用 的 provider(同 `content call` 的做法),把 Binder 和自报的 uid/pid 交过去。 */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun deliver(n: String?, resumeOnly: Boolean = false): Outcome {
            if (n == null) return Outcome.Rejected
            // 包名和 uid 可能被重装的别的 App 占用:签名对不上就不交 Binder,撤掉改动退出
            if (!signedAsPinned()) {
                Log.e(TAG, "$packageName is missing or not signed with the pinned certificate; exiting")
                runCatching { service.releaseAll() }
                exitProcess(0)
            }
            // Recheck immediately before provider acquisition; the UID callback may have been
            // queued before a subsequent force-stop. Initial explicit startup is unaffected.
            if (resumeOnly && !mayResumeExistingApp()) return Outcome.Failed
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val token = Binder()
            val holder = try {
                am.javaClass.getMethod(
                    "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                    IBinder::class.java, String::class.java,
                ).invoke(am, authority, userId, token, TAG)
            } catch (t: Throwable) {
                Log.w(TAG, "getContentProviderExternal failed", t)
                return Outcome.Failed
            } ?: return Outcome.Failed
            try {
                // 同名 authority 只可能属于一个包;还是核对一下,不把 Binder 交给 宿主应用 以外的 App
                val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                if (info == null || info.packageName != packageName || info.applicationInfo?.uid != appUid) {
                    Log.e(TAG, "provider $authority does not belong to $packageName/$appUid")
                    return Outcome.Rejected
                }
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putString(HelperHandoff.KEY_NONCE, n)
                    putBinder(HelperHandoff.KEY_BINDER, service)
                    putInt(HelperHandoff.KEY_UID, Process.myUid())
                    putInt(HelperHandoff.KEY_PID, Process.myPid())
                    putString(HelperHandoff.KEY_APK, System.getProperty("java.class.path"))
                }
                val call = provider.javaClass.methods.first {
                    it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
                }
                val caller = AttributionSource.Builder(Process.myUid()).setPackageName(ShellContext.SHELL_PACKAGE).build()
                val result = call.invoke(provider, caller, authority, HelperHandoff.METHOD_ATTACH, null, extras) as Bundle?
                if (result?.getBoolean(HelperHandoff.KEY_ACCEPTED) != true) {
                    Log.w(TAG, "handoff rejected: ${result?.getString(HelperHandoff.KEY_REASON)}")
                    return Outcome.Rejected
                }
                val appBinder = result.getBinder(HelperHandoff.KEY_TOKEN) ?: return Outcome.Rejected
                nonce = result.getString(HelperHandoff.KEY_NEXT)
                appToken = appBinder
                cancelRecovery()
                try {
                    appBinder.linkToDeath({ worker.post { onAppDied(appBinder) } }, 0)
                } catch (t: Throwable) {
                    worker.post { onAppDied(appBinder) }
                }
                Log.i(TAG, "handed off to $packageName")
                return Outcome.Accepted
            } catch (t: Throwable) {
                Log.w(TAG, "handoff failed", t)
                return Outcome.Failed
            } finally {
                runCatching {
                    am.javaClass.getMethod(
                        "removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType,
                    ).invoke(am, authority, token, userId)
                }
            }
        }

        /** 宿主应用 进程没了:它建的屏、静音、剪贴板改动跟着撤掉(和服务结束时一样),助手本身留着。 */
        private fun onAppDied(token: IBinder) {
            if (appToken !== token) return
            appToken = null
            runCatching { service.releaseAll() }.onFailure { Log.w(TAG, "cleanup failed", it) }
            if (!installed()) {
                Log.i(TAG, "$packageName is gone; exiting")
                exitProcess(0)
            }
            Log.i(TAG, "$packageName died; waiting for it to come back")
            // The new UID-importance callback can precede this old token's death callback.
            // Polling also covers devices that reject the importance-listener registration.
            scheduleRecovery()
        }

        @SuppressLint("DiscouragedPrivateApi")
        private fun installed(): Boolean = runCatching {
            context.packageManager.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(context.packageManager, packageName, 0, userId)
            true
        }.getOrElse { t ->
            // 包不在了才退出;反射不可用时看不清,当作还在
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            cause !is android.content.pm.PackageManager.NameNotFoundException
        }

        /**
         * 盯着 宿主应用 的 uid:进程重新起来时(从不存在变成任一状态)系统会报一次,这时把 Binder 再交过去。
         * 用的是 ActivityManager 的 uid 重要性监听(要 PACKAGE_USAGE_STATS,shell 有),回调在 binder 线程。
         * 注册不上或与旧 Binder 死亡通知发生竞争时,由单个恢复轮询检查已经运行的 App。
         */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun watchUid() {
            runCatching {
                // 用 shell 包名的上下文建 ActivityManager:注册时系统按调用方包名核对权限
                val listenerClass = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
                val listener = Proxy.newProxyInstance(HelperProcess::class.java.classLoader, arrayOf(listenerClass)) { proxy, method, a ->
                    when (method.name) {
                        "onUidImportance" -> { onUidImportance(a[0] as Int, a[1] as Int); null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === a?.getOrNull(0)
                        "toString" -> "AndroidControlHelperUidListener"
                        else -> null
                    }
                }
                ActivityManager::class.java.getMethod("addOnUidImportanceListener", listenerClass, Int::class.javaPrimitiveType)
                    .invoke(activityManager, listener, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
            }.onFailure { Log.w(TAG, "cannot watch $packageName; using existing-UID recovery polling", it) }
        }

        private fun onUidImportance(uid: Int, importance: Int) {
            if (uid != appUid || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) return
            worker.post { scheduleRecovery() }
        }

        private fun mayResumeExistingApp(): Boolean = runCatching {
            val info = packageInfo(0).applicationInfo ?: return@runCatching false
            val importance = ActivityManager::class.java.getDeclaredMethod("getUidImportance", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activityManager, appUid) as Int
            allowHelperResume(importance, info.uid == appUid,
                info.flags and ApplicationInfo.FLAG_STOPPED != 0)
        }.getOrDefault(false)

        @SuppressLint("DiscouragedPrivateApi")
        private fun packageInfo(flags: Int): PackageInfo = if (userId == 0) {
            context.packageManager.getPackageInfo(packageName, flags)
        } else context.packageManager.javaClass.getMethod("getPackageInfoAsUser", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(context.packageManager, packageName, flags, userId) as PackageInfo

        /** 现在装着的 [packageName] 仍是这个 uid、签名证书指纹与启动时 宿主应用 给的一致。看不清一律当不一致。 */
        @SuppressLint("DiscouragedPrivateApi")
        private fun signedAsPinned(): Boolean = runCatching {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val info = packageInfo(flags)
            val signers = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
            info.applicationInfo?.uid == appUid && HelperHandoff.certPin(signers) == certPin
        }.getOrElse { t ->
            Log.w(TAG, "cannot read signing info of $packageName", t)
            false
        }

        companion object {
            private const val PER_USER_RANGE = 100_000
            private const val RECOVERY_POLL_MS = 2_000L
        }
    }
}

/** Resume is for an existing live UID only, never a package the user has force-stopped. */
internal fun allowHelperResume(importance: Int, sameUid: Boolean, stopped: Boolean): Boolean =
    sameUid && !stopped && importance in 0 until ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE

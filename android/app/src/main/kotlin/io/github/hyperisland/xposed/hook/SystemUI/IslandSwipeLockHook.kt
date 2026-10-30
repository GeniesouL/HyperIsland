package io.github.hyperisland.xposed.hook.SystemUI

import io.github.hyperisland.xposed.logWarn
import io.github.hyperisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 固化常驻岛：阻止左右滑动把常驻岛划走。
 *
 * SystemUI 在岛上左右滑动时会调用 Delegate 的
 * `onSwipeSmallIslandToHiddenAnimation` / `onSwipeBigToHiddenAnimation`。
 * 这里拦截这两个方法，仅当当前岛属于常驻岛通知时才放弃执行，其他岛不受影响。
 */
object IslandSwipeLockHook {

    private const val TAG = "HyperIsland[SwipeLock]"

    private const val DELEGATE_CLASS =
        "miui.systemui.dynamicisland.anim.DynamicIslandAnimationDelegate"

    private const val SWIPE_COORDINATOR_CLASS =
        "miui.systemui.dynamicisland.event.SwipeEventCoordinator"

    /** 与 KeepIslandHook.KEEP_ISLAND_NOTIF_ID 保持一致（0x4B494B49）。 */
    private const val KEEP_ISLAND_NOTIF_ID = 0x4B494B49

    private val SWIPE_HIDE_METHODS = listOf(
        "onSwipeSmallIslandToHiddenAnimation",
        "onSwipeBigToHiddenAnimation",
        "onSwipeSmallToTempHidden",
    )

    private val hookedLoaders = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun init(module: XposedModule, param: PackageLoadedParam) {
        HookUtils.hookDynamicClassLoaders(module, ClassLoader.getSystemClassLoader()) { classLoader ->
            val id = System.identityHashCode(classLoader)
            if (!hookedLoaders.add(id)) return@hookDynamicClassLoaders
            val clazz = runCatching {
                Class.forName(DELEGATE_CLASS, false, classLoader)
            }.getOrNull() ?: run {
                module.logWarn(TAG, "delegate class not found")
                return@hookDynamicClassLoaders
            }
            module.logWarn(TAG, "hooked delegate cl=$id")
            SWIPE_HIDE_METHODS.forEach { name ->
                val method = clazz.declaredMethods.firstOrNull {
                    it.name == name && it.parameterCount >= 1
                } ?: run {
                    module.logWarn(TAG, "method not found: $name")
                    return@forEach
                }
                runCatching {
                    module.hook(method).intercept { chain ->
                        val view = chain.args.getOrNull(0)
                        val notifId = view?.let { notificationIdOf(it) }
                        module.logWarn(TAG, "$name called notifId=$notifId")
                        if (view == null || notifId != KEEP_ISLAND_NOTIF_ID) {
                            return@intercept chain.proceed()
                        }
                        module.logWarn(TAG, "blocked $name for keep island")
                        null
                    }
                }.onFailure { error ->
                    module.logWarn(TAG, "hook $name failed: ${error.message}")
                }
            }
            hookSwipeCoordinator(module, classLoader)
        }
    }

    /** 记录 SwipeEventCoordinator 的滑动入口，确认滑动隐藏到底走哪条路径。 */
    private fun hookSwipeCoordinator(module: XposedModule, classLoader: ClassLoader) {
        val clazz = runCatching {
            Class.forName(SWIPE_COORDINATOR_CLASS, false, classLoader)
        }.getOrNull() ?: run {
            module.logWarn(TAG, "swipe coordinator not found")
            return
        }
        clazz.declaredMethods
            .filter { it.name == "handleSwipe" }
            .forEach { method ->
                runCatching {
                    module.hook(method).intercept { chain ->
                        val args = chain.args.joinToString(", ") { it?.toString() ?: "null" }
                        module.logWarn(TAG, "handleSwipe(${args.take(120)})")
                        chain.proceed()
                    }
                }.onFailure { error ->
                    module.logWarn(TAG, "hook handleSwipe failed: ${error.message}")
                }
            }
    }

    /**
     * 从岛内容 View 上取通知 id。
     * 优先解析 `getIslandKey()`，格式如 `0|pkg|notifId|null|uid`。
     */
    private fun notificationIdOf(view: Any): Int? {
        val key = invokeNoArg(view, "getIslandKey") as? String
        key?.split('|')?.getOrNull(2)?.toIntOrNull()?.let { return it }
        val data = invokeNoArg(view, "getCurrentIslandData")
        if (data != null) {
            val extras = invokeNoArg(data, "getExtras") as? android.os.Bundle
            val sbn = extras?.getParcelable(
                "miui.sbn",
                android.service.notification.StatusBarNotification::class.java,
            )
            return sbn?.id
        }
        return null
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            val method = clazz.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }
            if (method != null) {
                method.isAccessible = true
                return runCatching { method.invoke(target) }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }
}

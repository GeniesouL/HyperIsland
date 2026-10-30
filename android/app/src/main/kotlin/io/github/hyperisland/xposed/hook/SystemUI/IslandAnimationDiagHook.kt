package io.github.hyperisland.xposed.hook.SystemUI

import android.os.SystemClock
import io.github.hyperisland.xposed.logError
import io.github.hyperisland.xposed.logWarn
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 临时诊断 Hook（仅用于定位「常驻岛消失再重建」的动画阶段）。
 *
 * 说明：一律使用 [logWarn]，不依赖「调试日志」开关，保证日志一定落到 LSPosed。
 *
 * 输出内容：
 * 1. 每个相关类的完整方法/构造器签名（只打一次）；
 * 2. 这些方法被调用时的参数摘要、耗时、时间戳（按方法名限流，避免动画帧刷屏）；
 * 3. 岛状态机 `onStateChange` 的 transition 值。
 */
object IslandAnimationDiagHook {

    private const val TAG = "HyperIsland[AnimDiag]"

    private const val CONTROLLER_CLASS =
        "miui.systemui.dynamicisland.anim.DynamicIslandAnimationController"

    private const val COORDINATOR_CLASS =
        "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator"

    private val ANIMATOR_CLASSES = listOf(
        "miui.systemui.dynamicisland.anim.ui.animator.FakeViewAnimator",
        "miui.systemui.dynamicisland.anim.p110ui.animator.FakeViewAnimator",
        "miui.systemui.dynamicisland.anim.p120ui.animator.FakeViewAnimator",
    )

    /** 同一签名在 [THROTTLE_MS] 内只输出一次，避免动画帧把日志刷爆。 */
    private const val THROTTLE_MS = 120L

    private val lastLoggedAt = ConcurrentHashMap<String, Long>()

    private val hookedClassLoaders = ConcurrentHashMap.newKeySet<Int>()

    fun init(module: XposedModule, param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val id = System.identityHashCode(classLoader)
        if (!hookedClassLoaders.add(id)) return
        module.logWarn(TAG, "init cl=$id")

        dumpAndHook(module, classLoader, CONTROLLER_CLASS)
        ANIMATOR_CLASSES.forEach { dumpAndHook(module, classLoader, it) }
        hookStateTransition(module, classLoader)
    }

    private fun dumpAndHook(module: XposedModule, classLoader: ClassLoader, className: String) {
        val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        if (clazz == null) {
            module.logWarn(TAG, "class not found: $className")
            return
        }
        module.logWarn(
            TAG,
            "===== $className (methods=${clazz.declaredMethods.size}, ctors=${clazz.declaredConstructors.size}) =====",
        )
        clazz.declaredConstructors.forEach { ctor ->
            module.logWarn(
                TAG,
                "  <init>(${ctor.parameterTypes.joinToString(",") { it.simpleName }})",
            )
        }
        clazz.declaredMethods.forEach { method ->
            val mods = if (Modifier.isStatic(method.modifiers)) "static " else ""
            module.logWarn(
                TAG,
                "  $mods${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})" +
                    " -> ${method.returnType.simpleName}",
            )
        }
        clazz.declaredMethods.forEach { method ->
            runCatching {
                module.hook(method).intercept { chain ->
                    val startedAt = SystemClock.uptimeMillis()
                    val result = chain.proceed()
                    val endedAt = SystemClock.uptimeMillis()
                    val signature = "$className#${method.name}"
                    val last = lastLoggedAt[signature] ?: 0L
                    if (endedAt - last >= THROTTLE_MS) {
                        lastLoggedAt[signature] = endedAt
                        val args = chain.args.joinToString(", ") { arg ->
                            arg?.toString()?.take(48) ?: "null"
                        }
                        module.logWarn(
                            TAG,
                            "CALL t=$endedAt ${method.name}($args) cost=${endedAt - startedAt}ms",
                        )
                    }
                    result
                }
            }.onFailure { error ->
                module.logError(TAG, "hook failed ${method.name}: ${error.message}")
            }
        }
    }

    private fun hookStateTransition(module: XposedModule, classLoader: ClassLoader) {
        val clazz = runCatching {
            Class.forName(COORDINATOR_CLASS, false, classLoader)
        }.getOrNull() ?: run {
            module.logWarn(TAG, "coordinator not found")
            return
        }
        clazz.declaredMethods
            .filter { it.name == "onStateChange" }
            .forEach { method ->
                runCatching {
                    module.hook(method).intercept { chain ->
                        val transition = chain.args.getOrNull(0)?.toString()
                        module.logWarn(
                            TAG,
                            "STATE t=${SystemClock.uptimeMillis()} transition=$transition",
                        )
                        chain.proceed()
                    }
                }.onFailure { error ->
                    module.logError(TAG, "state hook failed: ${error.message}")
                }
            }
    }
}

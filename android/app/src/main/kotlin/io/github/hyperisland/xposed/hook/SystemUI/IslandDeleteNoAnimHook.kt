package io.github.hyperisland.xposed.hook.SystemUI

import io.github.hyperisland.xposed.logWarn
import io.github.hyperisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.ConcurrentHashMap

/**
 * 实验：把「岛被删除时的收起动画」替换成无动画版本。
 *
 * 背景：真实通知结束时，SystemUI 会先播 [bigIslandToDeletedAnimation] 把岛缩回去，
 * 再由常驻岛重新 `initToBigIslandAnimation` 展开，形成「缩一下再弹回」。
 * 这里把收起那一步换成对应的 `NoAnimation`，让岛瞬间消失、只保留展开动画。
 */
object IslandDeleteNoAnimHook {

    private const val TAG = "HyperIsland[NoAnim]"

    private const val CONTROLLER_CLASS =
        "miui.systemui.dynamicisland.anim.DynamicIslandAnimationController"

    /** 有动画方法名 -> 对应的无动画方法名。 */
    private val PAIRS = listOf(
        "bigIslandToDeletedAnimation" to "bigIslandToDeletedNoAnimation",
        "smallIslandToDeletedAnimation" to "smallIslandToDeletedNoAnimation",
    )

    private val hookedLoaders = ConcurrentHashMap.newKeySet<Int>()

    fun init(module: XposedModule, param: PackageLoadedParam) {
        HookUtils.hookDynamicClassLoaders(module, ClassLoader.getSystemClassLoader()) { classLoader ->
            val id = System.identityHashCode(classLoader)
            if (!hookedLoaders.add(id)) return@hookDynamicClassLoaders
            val clazz = runCatching {
                Class.forName(CONTROLLER_CLASS, false, classLoader)
            }.getOrNull() ?: return@hookDynamicClassLoaders
            module.logWarn(TAG, "hooked cl=$id")
            PAIRS.forEach { (animatedName, noAnimName) ->
                val animated = clazz.declaredMethods.firstOrNull {
                    it.name == animatedName && it.parameterCount == 1
                } ?: return@forEach
                runCatching {
                    module.hook(animated).intercept { chain ->
                        val view = chain.args.getOrNull(0)
                        val noAnim = view?.let { findDelegateNoAnimation(chain.thisObject, it, noAnimName) }
                        if (view == null || noAnim == null) {
                            return@intercept chain.proceed()
                        }
                        val applied = runCatching { noAnim.invoke() }
                        if (applied.isFailure) {
                            module.logWarn(
                                TAG,
                                "$animatedName fallback: ${applied.exceptionOrNull()?.message}",
                            )
                            return@intercept chain.proceed()
                        }
                        module.logWarn(TAG, "skip $animatedName (instant)")
                        null
                    }
                }.onFailure { error ->
                    module.logWarn(TAG, "hook $animatedName failed: ${error.message}")
                }
            }
        }
    }

    /**
     * 找到 delegate 上的无动画方法，并返回一个已绑定参数的调用器。
     * Controller 的 `getAnimator(view)` 返回 [DynamicIslandAnimationDelegate]。
     */
    private fun findDelegateNoAnimation(
        controller: Any,
        view: Any,
        noAnimName: String,
    ): (() -> Any?)? {
        val delegate = invokeDeclared(controller, "getAnimator", view) ?: return null
        var clazz: Class<*>? = delegate.javaClass
        while (clazz != null) {
            val method = clazz.declaredMethods.firstOrNull {
                it.name == noAnimName && it.parameterCount == 1
            }
            if (method != null) {
                method.isAccessible = true
                return { runCatching { method.invoke(delegate, view) }.getOrNull() }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun invokeDeclared(target: Any, name: String, vararg args: Any?): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            val method = clazz.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == args.size
            }
            if (method != null) {
                method.isAccessible = true
                return runCatching { method.invoke(target, *args) }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }
}

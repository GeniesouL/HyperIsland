package io.github.hyperisland.xposed.hook.SystemUI

import io.github.hyperisland.xposed.logWarn
import io.github.hyperisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.ConcurrentHashMap

/**
 * 交接动画重定向。
 *
 * 两条规则：
 * 1. 「删除」改成「临时隐藏」：岛不再被销毁，恢复时走 tempHidden 路径，比从零 init 展开快；
 * 2. 从临时隐藏恢复时使用 `NoAnimation` 版本：岛瞬间出现，不播放「从零长大」的展开动画，
 *    从而消除通知结束后的空白间隔。
 *
 * 风险：第 2 条会让恢复变成硬切，可能出现单帧闪烁；实验确认后再决定是否保留。
 */
object IslandDeleteNoAnimHook {

    private const val TAG = "HyperIsland[NoAnim]"

    private const val CONTROLLER_CLASS =
        "miui.systemui.dynamicisland.anim.DynamicIslandAnimationController"

    private data class Redirect(
        val animatedName: String,
        val animatedArity: Int,
        val targetName: String,
    )

    /**
     * 源方法参数个数不同（删除动画 1 个参数，tempHidden 恢复 2 个参数），
     * 目标方法统一是 `(DynamicIslandContentView)` 单参数。
     *
     * 注意：不要把 `initToBigIslandAnimation` 重定向到 `bigIslandChangedNoAnimation`——
     * 实测那样不会推进状态机（Init → BigIsland），岛会直接消失。
     */
    private val PAIRS = listOf(
        Redirect("bigIslandToDeletedAnimation", 1, "bigIslandToTempHiddenAnimation"),
        Redirect("smallIslandToDeletedAnimation", 1, "smallIslandToTempHiddenAnimation"),
        Redirect("tempHiddenToBigIslandAnimation", 2, "tempHiddenToBigIslandNoAnimation"),
        Redirect("tempHiddenToSmallIslandAnimation", 2, "tempHiddenToSmallIslandNoAnimation"),
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
            PAIRS.forEach { redirect ->
                val animated = clazz.declaredMethods.firstOrNull {
                    it.name == redirect.animatedName && it.parameterCount == redirect.animatedArity
                } ?: return@forEach
                runCatching {
                    module.hook(animated).intercept { chain ->
                        val view = chain.args.getOrNull(0)
                        val target = view?.let {
                            findDelegateMethod(chain.thisObject, it, redirect.targetName)
                        }
                        if (view == null || target == null) {
                            return@intercept chain.proceed()
                        }
                        val applied = runCatching { target.invoke() }
                        if (applied.isFailure) {
                            module.logWarn(
                                TAG,
                                "${redirect.animatedName} fallback: " +
                                    "${applied.exceptionOrNull()?.message}",
                            )
                            return@intercept chain.proceed()
                        }
                        module.logWarn(
                            TAG,
                            "redirect ${redirect.animatedName} -> ${redirect.targetName}",
                        )
                        null
                    }
                }.onFailure { error ->
                    module.logWarn(TAG, "hook ${redirect.animatedName} failed: ${error.message}")
                }
            }
        }
    }

    /**
     * 在 delegate 上找 `(DynamicIslandContentView)` 单参数目标方法，返回已绑定参数的调用器。
     * Controller 的 `getAnimator(view)` 返回 `DynamicIslandAnimationDelegate`。
     */
    private fun findDelegateMethod(
        controller: Any,
        view: Any,
        targetName: String,
    ): (() -> Any?)? {
        val delegate = invokeDeclared(controller, "getAnimator", view) ?: return null
        var clazz: Class<*>? = delegate.javaClass
        while (clazz != null) {
            val method = clazz.declaredMethods.firstOrNull {
                it.name == targetName && it.parameterCount == 1
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

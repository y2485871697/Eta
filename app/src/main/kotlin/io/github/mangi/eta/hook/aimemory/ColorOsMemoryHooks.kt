package io.github.mangi.eta.hook.aimemory

import android.content.ContentProvider
import android.database.sqlite.SQLiteDatabase
import android.os.Binder
import android.os.Bundle
import io.github.mangi.eta.agent.tool.ColorOsMemoryDatabaseQuery
import io.github.mangi.eta.core.ColorOsMemoryBridgeProtocol
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.libxposed.api.XposedModule
import org.json.JSONObject

internal object ColorOsMemoryHooks {
    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ColorOsMemory")
        return hooks.install {
            val providerClass = HookSupport.findClassOrNull(
                classLoader,
                ColorOsMemoryBridgeProtocol.PROVIDER_CLASS,
            )
            if (providerClass == null) {
                hooks.missing(
                    id = "coloros-memory.provider-call",
                    description = "小布记忆 DataShareProvider.call",
                    detail = "未找到小布记忆 DataShareProvider，跳过系统记忆桥接",
                )
                return@install
            }
            val callMethod = HookSupport.findMethod(
                providerClass,
                "call",
                String::class.java,
                String::class.java,
                Bundle::class.java,
            )
            if (callMethod == null) {
                hooks.missing(
                    id = "coloros-memory.provider-call",
                    description = "小布记忆 DataShareProvider.call",
                    detail = "未找到 DataShareProvider.call(String,String,Bundle)",
                )
                return@install
            }
            hooks.intercept(
                id = "coloros-memory.provider-call",
                executable = callMethod,
                description = "小布记忆进程内只读查询桥",
            ) { chain ->
                val method = chain.args.getOrNull(0) as? String
                if (method != ColorOsMemoryBridgeProtocol.METHOD) {
                    return@intercept chain.proceed()
                }
                handleBridgeCall(
                    provider = chain.thisObject as? ContentProvider,
                    encodedRequest = chain.args.getOrNull(1) as? String,
                )
            }
        }
    }

    private fun handleBridgeCall(
        provider: ContentProvider?,
        encodedRequest: String?,
    ): Bundle {
        if (Binder.getCallingUid() != ROOT_UID) {
            return response(error("COLOROS_MEMORY_HOOK_CALLER_REJECTED", "系统记忆查询调用方无权限"))
        }
        val request = encodedRequest
            ?.let(ColorOsMemoryBridgeProtocol::decodeRequest)
            ?: return response(error("COLOROS_MEMORY_HOOK_REQUEST_INVALID", "系统记忆查询参数无效"))
        val context = provider?.context
            ?: return response(error("COLOROS_MEMORY_HOOK_CONTEXT_UNAVAILABLE", "小布记忆上下文不可用"))
        val databaseFile = context.getDatabasePath(ColorOsMemoryBridgeProtocol.DATABASE_NAME)
        if (!databaseFile.isFile) {
            return response(error("COLOROS_MEMORY_DATABASE_MISSING", "小布记忆数据库不存在"))
        }
        val content = runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { database ->
                ColorOsMemoryDatabaseQuery.execute(database, request.operation, request.args)
            }
        }.getOrElse {
            error("COLOROS_MEMORY_HOOK_QUERY_FAILED", "小布记忆进程内查询失败")
        }
        return response(content)
    }

    private fun response(content: String): Bundle {
        val encoded = runCatching { ColorOsMemoryBridgeProtocol.encodeResponse(content) }
            .getOrElse {
                ColorOsMemoryBridgeProtocol.encodeResponse(
                    error("COLOROS_MEMORY_HOOK_RESULT_TOO_LARGE", "系统记忆查询结果过大"),
                )
            }
        return Bundle().apply {
            putString(ColorOsMemoryBridgeProtocol.RESULT_KEY, encoded)
        }
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private const val ROOT_UID = 0
}

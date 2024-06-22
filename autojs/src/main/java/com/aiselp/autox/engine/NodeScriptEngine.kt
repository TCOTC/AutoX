package com.aiselp.autox.engine

import android.content.Context
import android.util.Log
import com.aiselp.autox.api.JavaInteractor
import com.aiselp.autox.api.JsToast
import com.aiselp.autox.api.NodeConsole
import com.aiselp.autox.module.NodeModuleResolver
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.exceptions.JavetExecutionException
import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.converters.JavetProxyConverter
import com.caoccao.javet.node.modules.NodeModuleModule
import com.caoccao.javet.node.modules.NodeModuleProcess
import com.caoccao.javet.values.V8Value
import com.caoccao.javet.values.reference.V8ValuePromise
import com.stardust.autojs.AutoJs
import com.stardust.autojs.BuildConfig
import com.stardust.autojs.engine.ScriptEngine
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.runtime.exception.ScriptException
import com.stardust.autojs.script.ScriptSource
import com.stardust.pio.PFiles
import com.stardust.util.UiHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.io.File

class NodeScriptEngine(val context: Context, val uiHandler: UiHandler) :
    ScriptEngine.AbstractScriptEngine<ScriptSource>() {
    private val runtime: NodeRuntime = V8Host.getNodeInstance().createV8Runtime()

    private val tags = mutableMapOf<String, Any?>()
    private val config: ExecutionConfig by lazy {
        tags[ExecutionConfig.tag] as ExecutionConfig
    }
    private val moduleDirectory = getModuleDirectory(context)
    private val resultListener = PromiseListener()
    private val console = NodeConsole(AutoJs.instance.globalConsole)
    private val nativeApiManager = NativeApiManager()
    private val converter = JavetProxyConverter()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val eventLoopQueue = EventLoopQueue()

    init {
        Log.i(TAG, "node version: ${runtime.version}")
    }

    override fun put(name: String, value: Any?) {
        tags[name] = value
    }

    override fun forceStop() {
        Log.i(TAG, "force stop")
        resultListener.cancel()
        if (runtime.isInUse) {
            runtime.terminateExecution()
        }
        if (scope.isActive) scope.cancel("force stop")
//        runtime.getNodeModule(NodeModuleProcess::class.java)
//            .moduleObject.invokeVoid("exit", runtime.createV8ValueInteger(1))
    }

    override fun init() {
        runtime.converter = converter
        initializeApi()
    }

    private fun initializeApi() = runtime.globalObject.use { global ->
        nativeApiManager.register(console)
        nativeApiManager.register(JavaInteractor(scope, converter, eventLoopQueue))
        nativeApiManager.register(JsToast(context, scope))
        nativeApiManager.initialize(runtime, global)
    }

    override fun execute(scriptSource: ScriptSource): Any? = runBlocking {
        check(scriptSource is NodeScriptSource) { "scriptSource must be NodeScriptSource" }
        Log.i(TAG, "execute: ${scriptSource.file.path}")
        val scriptFile = scriptSource.file
        try {
            initializeModule(scriptFile).use {
                if (it is V8ValuePromise)
                    it.register(resultListener)
                else resultListener.onFulfilled(it)
                while (scope.isActive) {
                    if (runtime.await(V8AwaitMode.RunNoWait) or
                        eventLoopQueue.executeQueue()
                    ) continue else break
                }
            }
            return@runBlocking resultListener.await().let {
                if (resultListener.stack != null) console.error(resultListener.stack)
                if (resultListener.isRejectedCalled) throw ScriptException(it)
            }
        } catch (e: JavetExecutionException) {
            throw e.apply { console.error(scriptingError.stack) }
        } catch (e: Throwable) {
            throw e.apply { console.error(toString()) }
        }
    }

    private fun initializeModule(file: File): V8Value {
        val parentFile = file.parentFile ?: File("/")
        runtime.getNodeModule(NodeModuleProcess::class.java).workingDirectory = parentFile
        runtime.getNodeModule(NodeModuleModule::class.java).setRequireRootDirectory(parentFile)
        val nodeModuleResolver = NodeModuleResolver(parentFile, moduleDirectory)
        runtime.v8ModuleResolver = nodeModuleResolver
        return if (NodeModuleResolver.isEsModule(file)) {
            //es module
            runtime.getExecutor(file).setResourceName(file.path).compileV8Module(true).run {
                nodeModuleResolver.addCacheModule(this)
                execute()
            }
        } else {
            //commonjs
            runtime.globalObject.invoke(
                NodeModuleModule.PROPERTY_REQUIRE, runtime.createV8ValueString(file.path)
            )
        }
    }

    override fun destroy() {
        nativeApiManager.recycle(runtime, runtime.globalObject)
        if (scope.isActive) scope.cancel()
        if (!runtime.isClosed) {
            runtime.lowMemoryNotification()
            runtime.close()
        }
        super.destroy()
    }

    companion object {
        const val ID = "com.aiselp.autox.engine.NodeScriptEngine"
        private const val TAG = "NodeScriptEngine"
        fun getModuleDirectory(context: Context): File {
            return File(context.filesDir, "node_modules")
        }

        fun initModuleResource(context: Context, appVersionChange: Boolean) {
            val moduleDirectory = getModuleDirectory(context)
            if (appVersionChange || BuildConfig.DEBUG || !moduleDirectory.isDirectory) {
                PFiles.removeDir(moduleDirectory.path)
                moduleDirectory.mkdirs()
                PFiles.copyAssetDir(context.assets, "modules/npm", moduleDirectory)
                PFiles.copyAssetDir(context.assets, "v7modules", moduleDirectory)
                initPackageFile(moduleDirectory)
            }
        }

        private fun initPackageFile(dir: File) {
            dir.listFiles()?.forEach {
                if (it.isDirectory) {
                    val packageJsonFile = File(it, "package.json")
                    if (!packageJsonFile.isFile()) {
                        packageJsonFile.writeText(
                            """
                            {
                                "name": "${it.name}",
                                "version": "0.0.0",
                                "main": "index.js"
                            }
                        """.trimIndent()
                        )
                    }
                }
            }
        }
    }
}
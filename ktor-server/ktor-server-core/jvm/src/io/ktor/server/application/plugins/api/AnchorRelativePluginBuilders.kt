/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.application.plugins.api

import io.ktor.server.application.*
import io.ktor.util.pipeline.*

/**
 *
 **/
public abstract class AnchorRelativePluginBuilder<AnchorContextT: PluginAnchorContext>(
    protected val currentPlugin: PluginBuilder<*>,
    protected val anchor: PluginAnchor<AnchorContextT>
) {
    public val anchorContext: AnchorContextT = anchor.context

    private fun <T : Any> sortedPhases(
        interceptions: List<Interception<T>>,
        pipeline: Pipeline<*, ApplicationCall>,
        otherPlugin: PluginBuilder<*>
    ): List<PipelinePhase> =
        interceptions
            .map { it.phase }
            .sortedBy {
                if (!pipeline.items.contains(it)) {
                    throw MissingApplicationPluginException(otherPlugin.key)
                }

                pipeline.items.indexOf(it)
            }

    /**
     *
     **/
    protected abstract fun selectPhase(): PipelinePhase?

    /**
     *
     **/
    protected abstract fun insertPhase(
        pipeline: Pipeline<*, ApplicationCall>,
        relativePhase: PipelinePhase,
        newPhase: PipelinePhase
    )

    private fun <T : Any, ContextT : CallHandlingContext> insertToPhaseRelativelyWithMessage(
        currentInterceptions: MutableList<Interception<T>>,
        contextInit: (PipelineContext<T, ApplicationCall>) -> ContextT,
        block: suspend ContextT.(ApplicationCall, Any) -> Unit
    ) {
        val currentPhase = currentPlugin.newPhase()

        currentInterceptions.add(
            Interception(
                currentPhase,
                action = { pipeline ->
                    selectPhase()?.let { lastDependentPhase ->
                        insertPhase(pipeline, lastDependentPhase, currentPhase)
                    }

                    pipeline.intercept(currentPhase) {
                        contextInit(this).block(call, subject)
                    }
                }
            )
        )
    }

    private fun <T : Any, ContextT : CallHandlingContext> insertToPhaseRelatively(
        currentInterceptions: MutableList<Interception<T>>,
        contextInit: (PipelineContext<T, ApplicationCall>) -> ContextT,
        block: suspend ContextT.(ApplicationCall) -> Unit
    ) = insertToPhaseRelativelyWithMessage(currentInterceptions, contextInit) { call, _ ->
        block(call)
    }

    internal fun onCall(block: suspend CallContext.(ApplicationCall) -> Unit) {
        insertToPhaseRelatively(
            currentPlugin.callInterceptions,
            ::CallContext
        ) { call -> block(call) }
    }

    internal fun onCallReceive(block: suspend CallReceiveContext.(ApplicationCall) -> Unit) {
        insertToPhaseRelatively(
            currentPlugin.onReceiveInterceptions,
            ::CallReceiveContext,
            block
        )
    }

    internal fun onCallRespond(block: suspend CallRespondContext.(ApplicationCall) -> Unit) {
        insertToPhaseRelatively(
            currentPlugin.onResponseInterceptions,
            ::CallRespondContext,
            block
        )
    }

    internal fun onCallRespondAfterTransform(
        block: suspend CallRespondAfterTransformContext.(ApplicationCall, Any) -> Unit
    ) {
        insertToPhaseRelativelyWithMessage(
            currentPlugin.afterResponseInterceptions,
            ::CallRespondAfterTransformContext,
            block
        )
    }


    @Deprecated(
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith("this@createPlugin.applicationShutdownHook"),
        message = "Please note that applicationShutdownHook is not guaranteed to be executed before " +
            "or after another plugin"
    )
    public fun applicationShutdownHook(hook: (Application) -> Unit) {
        currentPlugin.environment?.monitor?.subscribe(ApplicationStopped) { app ->
            hook(app)
        }
    }
}

/**
 * Contains handlers executed after the same handler is finished for all [otherPlugins].
 **/
public class AfterAnchorBuilder<AnchorContextT : PluginAnchorContext>(
    currentPlugin: PluginBuilder<*>,
    anchor: PluginAnchor<AnchorContextT>
) : AnchorRelativePluginBuilder<AnchorContextT>(currentPlugin, anchor) {
    override fun selectPhase(): PipelinePhase? = anchor.selectPhaseAfter(currentPlugin)

    override fun insertPhase(
        pipeline: Pipeline<*, ApplicationCall>,
        relativePhase: PipelinePhase,
        newPhase: PipelinePhase
    ) {
        pipeline.insertPhaseAfter(relativePhase, newPhase)
    }
}

/**
 * Contains handlers executed before the same handler is finished for all [otherPlugins].
 **/
public class BeforeAnchorBuilder<AnchorContextT: PluginAnchorContext>(
    currentPlugin: PluginBuilder<*>,
    anchor: PluginAnchor<AnchorContextT>
) : AnchorRelativePluginBuilder<AnchorContextT>(currentPlugin, anchor) {
    override fun selectPhase(): PipelinePhase? = anchor.selectPhaseBefore(currentPlugin)

    override fun insertPhase(
        pipeline: Pipeline<*, ApplicationCall>,
        relativePhase: PipelinePhase,
        newPhase: PipelinePhase
    ) {
        pipeline.insertPhaseBefore(relativePhase, newPhase)
    }
}

private class MyAnchorContext : PluginAnchorContext(builder) {
    public fun onCall(block: suspend CallContext.(ApplicationCall) -> Unit) = onCallImpl(block)
}


private object MyAnchor : PluginAnchor.OnCallPluginAnchor<MyAnchorContext> {

    override val context: MyAnchorContext
        get() = TODO("Not yet implemented")

    override val phase: PipelinePhase
        get() = TODO("Not yet implemented")

}

private fun f() {
    createApplicationPlugin("P") {
        beforeAnchor(MyAnchor) {
            onCall {

            }
        }
    }
}

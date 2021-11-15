/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.application.plugins.api

import io.ktor.server.application.*
import io.ktor.util.pipeline.*
import kotlin.random.*

public open class PluginAnchorContext(private val builder: AnchorRelativePluginBuilder<*>) {
    protected fun onCallImpl(block: suspend CallContext.(ApplicationCall) -> Unit): Unit =
        builder.onCall(block)

    protected fun onCallReceiveImpl(block: suspend CallReceiveContext.(ApplicationCall) -> Unit): Unit =
        builder.onCallReceive(block)

    protected fun onCallRespondImpl(block: suspend CallRespondContext.(ApplicationCall) -> Unit): Unit =
        builder.onCallRespond(block)

    protected fun onCallRespondAfterTransformImpl(
        block: suspend CallRespondAfterTransformContext.(ApplicationCall, Any) -> Unit
    ): Unit = builder.onCallRespondAfterTransform(block)
}

public sealed interface PluginAnchor<ContextT : PluginAnchorContext> {
    public val phase: PipelinePhase

    public fun selectPhaseBeforeIn(pipeline: Pipeline<*, ApplicationCall>): PipelinePhase? {
        if (phase !in pipeline.items) {
            val currentPhase = newPhase()
            pipeline.insertPhaseBefore(phase, currentPhase)
            return currentPhase
        }

        return phase
    }

    public fun newPhase(): PipelinePhase = PipelinePhase("AnchorPhase${Random.nextInt()}")

    public fun selectPhaseAfterIn(pipeline: Pipeline<*, ApplicationCall>): PipelinePhase? {
        if (phase !in pipeline.items) {
            val currentPhase = newPhase()
            pipeline.insertPhaseAfter(phase, currentPhase)
            return currentPhase
        }

        return phase
    }

    public val context: ContextT

    public interface OnCallPluginAnchor<ContextT : PluginAnchorContext> : PluginAnchor<ContextT>

    public interface OnCallReceivePluginAnchor<ContextT : PluginAnchorContext> : PluginAnchor<ContextT>

    public interface OnCallRespondPluginAnchor<ContextT : PluginAnchorContext> : PluginAnchor<ContextT>
}

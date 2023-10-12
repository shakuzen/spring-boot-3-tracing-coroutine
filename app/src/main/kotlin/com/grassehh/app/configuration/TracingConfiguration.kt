package com.grassehh.app.configuration

import io.micrometer.context.ContextRegistry
import io.micrometer.context.ContextSnapshotFactory
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Hooks
import reactor.netty.Metrics

@Configuration
class TracingConfiguration(private val observationRegistry: ObservationRegistry, private val tracer: Tracer) {

    @Bean
    fun coroutineWebFilter(): WebFilter {
        return object : CoWebFilter() {
            override suspend fun filter(exchange: ServerWebExchange, chain: CoWebFilterChain)
                    = withContext(MDCContext()) {
                chain.filter(exchange)
            }
        }
    }

    @Bean
    fun contextSnapshotFactory() = ContextSnapshotFactory.builder().build();

    @PostConstruct
    fun postConstruct() {
        Hooks.enableAutomaticContextPropagation()
        ContextRegistry.getInstance().registerThreadLocalAccessor(ObservationAwareSpanThreadLocalAccessor(tracer));
        ObservationThreadLocalAccessor.getInstance().observationRegistry = observationRegistry
        Metrics.observationRegistry(observationRegistry)
    }
}

class TracingChannelDuplexHandler(
    private val delegate: ChannelDuplexHandler,
    private val contextSnapshotFactory: ContextSnapshotFactory
) : ChannelDuplexHandler() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        contextSnapshotFactory.setThreadLocalsFrom<String>(ctx.channel()).use {
            delegate.channelRead(ctx, msg)
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise?) {
        contextSnapshotFactory.setThreadLocalsFrom<String>(ctx.channel()).use {
            delegate.write(ctx, msg, promise)
        }
    }
}
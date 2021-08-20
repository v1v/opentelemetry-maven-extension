/*
 * Copyright The Original Author or Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package co.elastic.maven.opentelemetry;

import co.elastic.maven.opentelemetry.semconv.MavenOtelSemanticAttributes;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Close the OpenTelemetry SDK (see {@link OpenTelemetrySdkService#close()} on the end of execution of the last project 
 * ({@link #projectSucceeded(ExecutionEvent)} and {@link #projectFailed(ExecutionEvent)}) rather than on the end of the 
 * Maven session  {@link #sessionEnded(ExecutionEvent)} because OpenTelemetry & GRPC classes are unloaded by the Maven 
 * classloader before {@link #sessionEnded(ExecutionEvent)} causing {@link NoClassDefFoundError} messages in the logs.
 */
@Component(role = ExecutionListener.class, hint = "otel-execution-listener")
public class OtelExecutionListener extends AbstractExecutionListener {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Requirement
    private SpanRegistry spanRegistry;

    @Requirement
    private OpenTelemetrySdkService openTelemetrySdkService;

    private final AtomicInteger projectsCounter = new AtomicInteger();

    @Override
    public void projectStarted(ExecutionEvent executionEvent) {
        MavenProject project = executionEvent.getProject();
        TextMapGetter<Map<String, String>> getter = new TextMapGetter<Map<String, String>>() {
            @Override
            public Iterable<String> keys(Map<String, String> environmentVariables) {
                return environmentVariables.keySet();
            }

            @Nullable
            @Override
            public String get(@Nullable Map<String, String> environmentVariables, String key) {
                return environmentVariables == null ? null : environmentVariables.get(key.toUpperCase(Locale.ROOT));
            }
        };
        io.opentelemetry.context.Context context = W3CTraceContextPropagator.getInstance().extract(io.opentelemetry.context.Context.current(), System.getenv(), getter);
        try (Scope scope = context.makeCurrent()) {
            final String spanName = project.getGroupId() + ":" + project.getArtifactId();
            logger.debug("Start project span {}", spanName);
            Span span = this.openTelemetrySdkService.getTracer().spanBuilder(spanName)
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_PROJECT_GROUP_ID, project.getGroupId())
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_PROJECT_ARTIFACT_ID, project.getArtifactId())
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_PROJECT_VERSION, project.getVersion())
                    .startSpan();
            // FIXME support multi module projects
            spanRegistry.setRootSpan(span);
        }
        projectsCounter.incrementAndGet();
    }

    @Override
    public void projectSucceeded(ExecutionEvent executionEvent) {
        try {
            logger.debug("End succeeded project span {}:{}", executionEvent.getProject().getArtifactId(), executionEvent.getProject().getArtifactId());
            spanRegistry.removeRootSpan().end();
        } finally {
            final int projectsCount = projectsCounter.decrementAndGet();
            if (projectsCount == 0) {
                logger.debug("Close openTelemetrySdkService...");
                try {
                    this.openTelemetrySdkService.close();
                } catch (IOException e) {
                    logger.warn("Silently ignore exception shutting down OpenTelemetry service", e);
                }
                logger.debug("OpenTelemetrySdkService closed");
            }
        }
    }

    @Override
    public void projectFailed(ExecutionEvent executionEvent) {
        try {
            logger.debug("End failed project span {}:{}", executionEvent.getProject().getArtifactId(), executionEvent.getProject().getArtifactId());
            final Span span = spanRegistry.removeRootSpan();
            span.setStatus(StatusCode.ERROR);
            span.recordException(executionEvent.getException());
            span.end();

        } finally {
            final int projectsCount = projectsCounter.decrementAndGet();
            if (projectsCount == 0) {
                logger.debug("Close openTelemetrySdkService...");
                try {
                    this.openTelemetrySdkService.close();
                } catch (IOException e) {
                    logger.warn("Silently ignore exception shutting down OpenTelemetry service", e);
                }
                logger.debug("OpenTelemetrySdkService closed");
            }
        }
    }

    @Override
    public void mojoStarted(ExecutionEvent executionEvent) {

        MojoExecution mojoExecution = executionEvent.getMojoExecution();

        Span rootSpan = spanRegistry.getRootSpan();
        try (Scope scope = rootSpan.makeCurrent()) {

            final String spanName = getPluginArtifactIdShortName(mojoExecution.getArtifactId()) + ":" + mojoExecution.getGoal() +
                    " (" + executionEvent.getMojoExecution().getExecutionId() + ")" +
                    " @ " + executionEvent.getProject().getArtifactId();
            logger.debug("Start mojo execution span {}", spanName);
            Span span = this.openTelemetrySdkService.getTracer().spanBuilder(
                            spanName)

                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_PROJECT_GROUP_ID, executionEvent.getProject().getGroupId())
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_PROJECT_ARTIFACT_ID, executionEvent.getProject().getArtifactId())
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_PROJECT_VERSION, executionEvent.getProject().getVersion())

                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_PLUGIN_GROUP_ID, mojoExecution.getPlugin().getGroupId())
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_PLUGIN_ARTIFACT_ID, mojoExecution.getPlugin().getArtifactId())
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_PLUGIN_VERSION, mojoExecution.getPlugin().getVersion())
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_EXECUTION_GOAL, mojoExecution.getGoal())
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_EXECUTION_ID, mojoExecution.getExecutionId())
                    .setAttribute(MavenOtelSemanticAttributes.MAVEN_EXECUTION_LIFECYCLE_PHASE, mojoExecution.getLifecyclePhase())
                    .startSpan();
            spanRegistry.putSpan(span, mojoExecution);
        }
    }

    @Override
    public void mojoSucceeded(ExecutionEvent executionEvent) {
        MojoExecution mojoExecution = executionEvent.getMojoExecution();
        logger.debug("End succeeded mojo execution span {}", mojoExecution);
        Span mojoExecutionSpan = spanRegistry.removeSpan(mojoExecution);
        mojoExecutionSpan.setStatus(StatusCode.OK);

        mojoExecutionSpan.end();
    }

    @Override
    public void mojoFailed(ExecutionEvent executionEvent) {
        MojoExecution mojoExecution = executionEvent.getMojoExecution();
        logger.debug("End failed mojo execution span {}", mojoExecution);
        Span mojoExecutionSpan = spanRegistry.removeSpan(mojoExecution);
        mojoExecutionSpan.setStatus(StatusCode.ERROR, "Mojo Failed"); // TODO verify description
        mojoExecutionSpan.end();
    }

    @Override
    public void sessionEnded(ExecutionEvent event) {
        logger.debug("Maven session ended");
    }

    /**
     * maven-clean-plugin -> clean
     * sisu-maven-plugin -> sisu
     * spotbugs-maven-plugin -> spotbugs
     *
     * @param pluginArtifactId the artifact ID of the mojo {@link MojoExecution#getArtifactId()}
     * @return shortened name
     */
    @Nonnull
    protected String getPluginArtifactIdShortName(@Nonnull String pluginArtifactId) {
        if (pluginArtifactId.endsWith("-maven-plugin")) {
            return pluginArtifactId.substring(0, pluginArtifactId.length() - "-maven-plugin".length());
        } else if (pluginArtifactId.startsWith("maven-") && pluginArtifactId.endsWith("-plugin")) {
            return pluginArtifactId.substring("maven-".length(), pluginArtifactId.length() - "-plugin".length());
        } else {
            return pluginArtifactId;
        }
    }


    /**
     * Register in given {@link OtelExecutionListener} to the lifecycle of the given {@link MavenSession} 
     * @see org.apache.maven.execution.MavenExecutionRequest#setExecutionListener(ExecutionListener)
     */
    public static void registerOtelExecutionListener(@Nonnull MavenSession session, @Nonnull OtelExecutionListener otelExecutionListener) {
        final ExecutionListener initialExecutionListener = session.getRequest().getExecutionListener();
        if (initialExecutionListener instanceof ChainedExecutionListener) {
            // already initialized
        } else {
            session.getRequest().setExecutionListener(new ChainedExecutionListener(otelExecutionListener, initialExecutionListener));
            LoggerFactory.getLogger(OtelExecutionListener.class).info("initialize - initialExecutionListener: " + initialExecutionListener);
        }
    }


}
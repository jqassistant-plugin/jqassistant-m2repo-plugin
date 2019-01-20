package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import com.buschmais.jqassistant.plugin.m2repo.api.ArtifactProvider;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.index.ArtifactInfo;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes an {@link ArtifactSearchResult} and provides the found artifacts as
 * {@link Result}.
 */
public class ArtifactTask implements Runnable {

    /**
     * The result containing the model artifact and optionally the artifact itself.
     */
    public static class Result {

        public static final Result LAST = new Result(null, null, null, -1);

        private final ArtifactInfo artifactInfo;

        private final ArtifactResult modelArtifactResult;

        private final Optional<ArtifactResult> artifactResult;

        private final long lastModified;

        public Result(ArtifactInfo artifactInfo, ArtifactResult modelArtifactResult, Optional<ArtifactResult> artifactResult, long lastModified) {
            this.artifactInfo = artifactInfo;
            this.modelArtifactResult = modelArtifactResult;
            this.artifactResult = artifactResult;
            this.lastModified = lastModified;
        }

        public ArtifactInfo getArtifactInfo() {
            return artifactInfo;
        }

        public ArtifactResult getModelArtifactResult() {
            return modelArtifactResult;
        }

        public Optional<ArtifactResult> getArtifactResult() {
            return artifactResult;
        }

        public long getLastModified() {
            return lastModified;
        }
    }

    private static final String EXTENSION_POM = "pom";

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactTask.class);

    private final ArtifactSearchResult artifactInfos;

    private final ArtifactFilter artifactFilter;

    private final boolean fetchArtifact;

    private final BlockingQueue<Result> queue;

    private final ArtifactProvider artifactProvider;

    /**
     * Constructor.
     * 
     * @param artifactSearchResult
     *            The {@link ArtifactSearchResult}.
     * @param artifactFilter
     *            The {@link ArtifactFilter}.
     * @param fetchArtifact
     *            if <code>true</code> the {@link Artifact} will be fetched,
     *            otherwise only the model {@link Artifact} (i.e. pom).
     * @param queue
     *            The {@link BlockingQueue} for publishing the {@link Result}s.
     * @param artifactProvider
     *            The {@link ArtifactProvider} for fetching the {@link Artifact}s.
     */
    ArtifactTask(ArtifactSearchResult artifactSearchResult, ArtifactFilter artifactFilter, boolean fetchArtifact, BlockingQueue<Result> queue,
            ArtifactProvider artifactProvider) {
        this.artifactInfos = artifactSearchResult;
        this.artifactFilter = artifactFilter;
        this.fetchArtifact = fetchArtifact;
        this.queue = queue;
        this.artifactProvider = artifactProvider;
    }

    @Override
    public void run() {
        try {
            for (ArtifactInfo artifactInfo : artifactInfos) {
                String groupId = artifactInfo.getGroupId();
                String artifactId = artifactInfo.getArtifactId();
                String classifier = artifactInfo.getClassifier();
                String packaging = artifactInfo.getPackaging();
                String version = artifactInfo.getVersion();
                long lastModified = artifactInfo.getLastModified();
                Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, packaging, version);
                if (!artifactFilter.match(RepositoryUtils.toArtifact(artifact))) {
                    LOGGER.debug("Skipping '{}'.", artifactInfo);
                } else {
                    Artifact modelArtifact = new DefaultArtifact(groupId, artifactId, null, EXTENSION_POM, version);
                    Optional<ArtifactResult> artifactResult;
                    try {
                        LOGGER.debug("Fetching model '{}'.", modelArtifact);
                        ArtifactResult modelArtifactResult = this.artifactProvider.getArtifact(modelArtifact);
                        if (fetchArtifact && !artifact.getExtension().equals(EXTENSION_POM)) {
                            LOGGER.debug("Fetching artifact '{}'.", artifact);
                            artifactResult = Optional.of(artifactProvider.getArtifact(artifact));
                        } else {
                            artifactResult = Optional.empty();
                        }
                        Result result = new Result(artifactInfo, modelArtifactResult, artifactResult, lastModified);
                        queue.put(result);
                    } catch (ArtifactResolutionException e) {
                        LOGGER.warn("Cannot resolve artifact '" + artifact + "'.", e);
                    }
                }
            }
            queue.put(Result.LAST);
        } catch (InterruptedException e) {
            LOGGER.warn("Task has been interrupted.", e);
        }
    }

}

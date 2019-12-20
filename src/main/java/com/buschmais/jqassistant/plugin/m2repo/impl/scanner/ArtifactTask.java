package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import com.buschmais.jqassistant.plugin.m2repo.api.ArtifactProvider;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactFilter;

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

        /**
         * Marker indicating that no more results are available.
         */
        public static final Result LAST = new Result(null, null, null);

        private final ArtifactInfo artifactInfo;

        private final Optional<ArtifactResult> modelArtifactResult;

        private final Optional<ArtifactResult> artifactResult;

        /**
         * Represents the result of resolving an artifact and its model based on an
         * {@link ArtifactInfo}.
         *
         * @param artifactInfo
         *            The {@link ArtifactInfo}.
         * @param modelArtifactResult
         *            The {@link ArtifactResult} of the model.
         * @param artifactResult
         *            The {@link ArtifactResult} of the artifact.
         */
        private Result(ArtifactInfo artifactInfo, Optional<ArtifactResult> modelArtifactResult, Optional<ArtifactResult> artifactResult) {
            this.artifactInfo = artifactInfo;
            this.modelArtifactResult = modelArtifactResult;
            this.artifactResult = artifactResult;
        }

        public ArtifactInfo getArtifactInfo() {
            return artifactInfo;
        }

        public Optional<ArtifactResult> getModelArtifactResult() {
            return modelArtifactResult;
        }

        public Optional<ArtifactResult> getArtifactResult() {
            return artifactResult;
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
                Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, packaging, version);
                if (!artifactFilter.match(RepositoryUtils.toArtifact(artifact))) {
                    LOGGER.debug("Skipping '{}'.", artifactInfo);
                } else {
                    Artifact modelArtifact = new DefaultArtifact(groupId, artifactId, null, EXTENSION_POM, version);
                    LOGGER.debug("Fetching model '{}'.", modelArtifact);
                    Optional<ArtifactResult> modelArtifactResult = getArtifact(modelArtifact);
                    Optional<ArtifactResult> artifactResult;
                    if (fetchArtifact && !artifact.getExtension().equals(EXTENSION_POM)) {
                        LOGGER.debug("Fetching artifact '{}'.", artifact);
                        artifactResult = getArtifact(artifact);
                    } else {
                        artifactResult = Optional.empty();
                    }
                    Result result = new Result(artifactInfo, modelArtifactResult, artifactResult);
                    queue.put(result);
                }
            }
            queue.put(Result.LAST);
        } catch (InterruptedException e) {
            LOGGER.warn("Task has been interrupted.", e);
        }
    }

    private Optional<ArtifactResult> getArtifact(Artifact artifact) {
        try {
            return Optional.of(this.artifactProvider.getArtifact(artifact));
        } catch (ArtifactResolutionException e) {
            LOGGER.warn("Cannot resolve artifact '" + artifact + "'.", e);
        }
        return Optional.empty();
    }

}

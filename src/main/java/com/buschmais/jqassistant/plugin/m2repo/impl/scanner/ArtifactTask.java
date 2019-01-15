package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import com.buschmais.jqassistant.plugin.m2repo.api.ArtifactProvider;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.MAVEN;
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

        private final ArtifactResult modelArtifactResult;

        private final Optional<ArtifactResult> artifactResult;

        private final Long lastModified;

        public Result(ArtifactResult modelArtifactResult, Optional<ArtifactResult> artifactResult, Long lastModified) {
            this.modelArtifactResult = modelArtifactResult;
            this.artifactResult = artifactResult;
            this.lastModified = lastModified;
        }

        public ArtifactResult getModelArtifactResult() {
            return modelArtifactResult;
        }

        public Optional<ArtifactResult> getArtifactResult() {
            return artifactResult;
        }

        public Long getLastModified() {
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

    public ArtifactTask(ArtifactSearchResult artifactInfos, ArtifactFilter artifactFilter, boolean fetchArtifact, BlockingQueue<Result> queue,
            ArtifactProvider artifactProvider) {
        this.artifactInfos = artifactInfos;
        this.artifactFilter = artifactFilter;
        this.fetchArtifact = fetchArtifact;
        this.queue = queue;
        this.artifactProvider = artifactProvider;
    }

    @Override
    public void run() {
        try {
            for (ArtifactInfo artifactInfo : artifactInfos) {
                String groupId = artifactInfo.getFieldValue(MAVEN.GROUP_ID);
                String artifactId = artifactInfo.getFieldValue(MAVEN.ARTIFACT_ID);
                String classifier = artifactInfo.getFieldValue(MAVEN.CLASSIFIER);
                String packaging = artifactInfo.getFieldValue(MAVEN.PACKAGING);
                String version = artifactInfo.getFieldValue(MAVEN.VERSION);
                String lastModifiedField = artifactInfo.getFieldValue(MAVEN.LAST_MODIFIED);
                Long lastModified = lastModifiedField != null ? Long.valueOf(lastModifiedField) : null;
                Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, packaging, version);
                if (!artifactFilter.match(RepositoryUtils.toArtifact(artifact))) {
                    LOGGER.info("Skipping '{}'.", artifactInfo);
                } else {
                    LOGGER.info("Scanning '{}'.", artifactInfo);
                    Artifact modelArtifact = new DefaultArtifact(groupId, artifactId, null, EXTENSION_POM, version);
                    Optional<ArtifactResult> artifactResult;
                    try {
                        ArtifactResult modelArtifactResult = this.artifactProvider.getArtifact(modelArtifact);
                        if (fetchArtifact && !artifact.getExtension().equals(EXTENSION_POM)) {
                            artifactResult = Optional.of(artifactProvider.getArtifact(artifact));
                        } else {
                            artifactResult = Optional.empty();
                        }
                        queue.put(new Result(modelArtifactResult, artifactResult, lastModified));
                    } catch (ArtifactResolutionException e) {
                        LOGGER.warn("Cannot resolve artifact " + artifact, e);
                    }
                }
            }
            queue.put(new Result(null, null, null));
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted...", e);
        }
    }
}

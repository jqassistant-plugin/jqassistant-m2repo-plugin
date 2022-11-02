package org.jqassistant.plugin.m2repo.api;

import java.io.File;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

/**
 * Defines the interface for an artifact provider
 */
public interface ArtifactProvider {

    /**
     * Provide the given artifact as {@link ArtifactResult}.
     *
     * @param artifact
     *            The artifact.
     * @return The {@link ArtifactResult}.
     * @throws ArtifactResolutionException
     *             If the artifact cannot be resolved.
     */
    ArtifactResult getArtifact(Artifact artifact) throws ArtifactResolutionException;

    /**
     * Return the local repository root directory.
     *
     * @return The repository root directory.
     */
    File getRepositoryRoot();
}

package org.jqassistant.plugin.m2repo.impl.scanner;

import com.buschmais.jqassistant.plugin.maven3.api.artifact.Coordinates;

import org.apache.maven.index.ArtifactInfo;

/**
 * Represents {@link Coordinates} based on an {@link ArtifactInfo}.
 */
public class ArtifactInfoCoordinates implements Coordinates {

    private final ArtifactInfo artifactInfo;

    public ArtifactInfoCoordinates(ArtifactInfo artifactInfo) {
        this.artifactInfo = artifactInfo;
    }

    @Override
    public String getGroup() {
        return artifactInfo.getGroupId();
    }

    @Override
    public String getName() {
        return artifactInfo.getArtifactId();
    }

    @Override
    public String getClassifier() {
        return artifactInfo.getClassifier();
    }

    @Override
    public String getType() {
        return artifactInfo.getPackaging();
    }

    @Override
    public String getVersion() {
        return artifactInfo.getVersion();
    }

    @Override
    public String toString() {
        return "ArtifactInfoCoordinates{" + "artifactInfo=" + artifactInfo + '}';
    }
}

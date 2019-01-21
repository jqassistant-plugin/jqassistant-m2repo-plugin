package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.text.SimpleDateFormat;
import java.util.Date;

import com.buschmais.jqassistant.plugin.maven3.api.artifact.Coordinates;

import org.apache.maven.index.ArtifactInfo;

/**
 * Represents {@link Coordinates} based on an {@link ArtifactInfo}.
 */
public class ArtifactInfoCoordinates implements Coordinates {

    private static final String DATEFORMAT_TIMESTAMP_SNAPSHOT = "yyyyMMddHHmmss";

    private final ArtifactInfo artifactInfo;

    private final String baseVersion;

    private final boolean snapshot;

    public ArtifactInfoCoordinates(ArtifactInfo artifactInfo, String baseVersion, boolean snapshot) {
        this.artifactInfo = artifactInfo;
        this.baseVersion = baseVersion;
        this.snapshot = snapshot;
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
        if (snapshot) {
            String timeStamp = new SimpleDateFormat(DATEFORMAT_TIMESTAMP_SNAPSHOT).format(new Date(artifactInfo.getLastModified()));
            return baseVersion + "-" + timeStamp;
        }
        return artifactInfo.getVersion();
    }
}

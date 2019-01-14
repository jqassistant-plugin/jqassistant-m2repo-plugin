package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.util.Iterator;

import org.apache.maven.index.ArtifactInfo;

/**
 * Represents a search result from the {@link MavenIndex}.
 */
public class ArtifactSearchResult implements Iterable<ArtifactInfo> {

    private final Iterable<ArtifactInfo> artifactInfos;

    public ArtifactSearchResult(Iterable<ArtifactInfo> artifactInfos) {
        this.artifactInfos = artifactInfos;
    }

    @Override
    public Iterator<ArtifactInfo> iterator() {
        return artifactInfos.iterator();
    }
}

package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;

import org.apache.maven.index.ArtifactInfo;

/**
 * Represents a search result from the {@link MavenIndex}.
 */
public class ArtifactSearchResult implements Iterable<ArtifactInfo>, Closeable {

    private final Iterable<ArtifactInfo> artifactInfos;

    public ArtifactSearchResult(Iterable<ArtifactInfo> artifactInfos) {
        this.artifactInfos = artifactInfos;
    }

    @Override
    public Iterator<ArtifactInfo> iterator() {
        return artifactInfos.iterator();
    }

    @Override
    public void close() throws IOException {
        if (artifactInfos instanceof Closeable) {
            ((Closeable) artifactInfos).close();
        }
    }
}

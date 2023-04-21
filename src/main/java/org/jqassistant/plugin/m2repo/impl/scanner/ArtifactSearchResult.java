package org.jqassistant.plugin.m2repo.impl.scanner;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;

import org.apache.maven.index.ArtifactInfo;

/**
 * Represents a search result from the {@link MavenIndex}.
 */
public class ArtifactSearchResult implements Iterable<ArtifactInfo>, Closeable {

    private final Iterable<ArtifactInfo> artifactInfos;

    private final int size;

    public ArtifactSearchResult(Iterable<ArtifactInfo> artifactInfos, int size) {
        this.artifactInfos = artifactInfos;
        this.size = size;
    }

    /**
     * Return the (estimated) size of the result.
     *
     * @return The size
     */
    public int getSize() {
        return size;
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

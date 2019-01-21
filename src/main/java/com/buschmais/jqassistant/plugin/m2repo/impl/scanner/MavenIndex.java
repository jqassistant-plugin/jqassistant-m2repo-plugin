package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.lucene.search.Query;
import org.apache.maven.index.*;
import org.apache.maven.index.context.ExistingLuceneIndexMismatchException;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.creator.JarFileContentsIndexCreator;
import org.apache.maven.index.creator.MavenArchetypeArtifactInfoIndexCreator;
import org.apache.maven.index.creator.MavenPluginArtifactInfoIndexCreator;
import org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator;
import org.apache.maven.index.expr.SourcedSearchExpression;
import org.apache.maven.index.updater.*;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.*;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class downloads and updates the remote maven index.
 * 
 * @author pherklotz
 */
public class MavenIndex implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenIndex.class);

    private IndexingContext indexingContext;

    private PlexusContainer plexusContainer;

    private Indexer indexer;

    private String password;

    private String username;

    /**
     * Constructs a new object.
     * 
     * @param repoUrl
     *            the repository url
     * @param repositoryDirectory
     *            the directory containing the local repository.
     * @param indexDirectory
     *            the directory for local index data
     * @throws IOException
     *             error during index creation/update
     */
    public MavenIndex(URL repoUrl, File repositoryDirectory, File indexDirectory, String username, String password) throws IOException {
        this.username = username;
        this.password = password;
        try {
            createIndexingContext(repoUrl, repositoryDirectory, indexDirectory);
        } catch (IllegalArgumentException | PlexusContainerException | ComponentLookupException e) {
            throw new IOException(e);
        }
    }

    /**
     * Creates a new {@link IndexingContext}.
     * 
     * @param repoUrl
     *            the URL of the remote Repository.
     * @param indexDirectory
     *            the dir for local index data
     * @throws ComponentLookupException
     * @throws ExistingLuceneIndexMismatchException
     * @throws IllegalArgumentException
     * @throws IOException
     */
    private void createIndexingContext(URL repoUrl, File repositoryDirectory, File indexDirectory)
            throws PlexusContainerException, ComponentLookupException, ExistingLuceneIndexMismatchException, IllegalArgumentException, IOException {
        DefaultContainerConfiguration config = new DefaultContainerConfiguration();
        config.setClassPathScanning(PlexusConstants.SCANNING_INDEX);
        plexusContainer = new DefaultPlexusContainer(config);
        indexer = plexusContainer.lookup(DefaultIndexer.class);
        // Files where local cache is (if any) and Lucene Index should be located
        String repoSuffix = repoUrl.getHost();
        File localIndexDir = new File(indexDirectory, "repo-index");
        // Creators we want to use (search for fields it defines)
        List<IndexCreator> indexers = new ArrayList<>();
        indexers.add(plexusContainer.lookup(MinimalArtifactInfoIndexCreator.class));
        indexers.add(plexusContainer.lookup(JarFileContentsIndexCreator.class));
        indexers.add(plexusContainer.lookup(MavenPluginArtifactInfoIndexCreator.class));
        indexers.add(plexusContainer.lookup(MavenArchetypeArtifactInfoIndexCreator.class));

        // Create context for central repository index
        indexingContext = indexer.createIndexingContext("jqa-cxt-" + repoSuffix, "jqa-repo-id-" + repoSuffix, repositoryDirectory, localIndexDir,
                repoUrl.toString(), null, true, true, indexers);
    }

    public void close() throws IOException {
        indexer.closeIndexingContext(indexingContext, false);
    }

    public ArtifactSearchResult getArtifactsSince(final Date startDate) throws IOException {
        final long startDateMillis = startDate.getTime();
        // find only maven artifact documents
        Query query = indexer.constructQuery(MAVEN.GROUP_ID, new SourcedSearchExpression(Field.NOT_PRESENT));
        IteratorSearchRequest request = new IteratorSearchRequest(query, Collections.singletonList(indexingContext),
                (ctx, ai) -> startDateMillis < ai.getLastModified());
        return new ArtifactSearchResult(indexer.searchIterator(request));
    }

    /**
     * Returns a timestamp of the last local repository index update.
     * 
     * @return A timestamp of the last local repository index update.
     */
    public Date getLastUpdateLocalRepo() {
        return indexingContext.getTimestamp();
    }

    /**
     * Update the local index.
     * 
     * @throws ComponentLookupException
     * @throws IOException
     */
    public void updateIndex() throws IOException {
        IndexUpdater indexUpdater;
        Wagon httpWagon;
        try {
            indexUpdater = plexusContainer.lookup(DefaultIndexUpdater.class);
            httpWagon = plexusContainer.lookup(Wagon.class, "http");
        } catch (ComponentLookupException e) {
            throw new IOException(e);
        }

        LOGGER.info("Updating repository index (this may take a while).");
        TransferListener listener = new AbstractTransferListener() {
            @Override
            public void transferCompleted(TransferEvent transferEvent) {
                LOGGER.debug("Downloading " + transferEvent.getResource().getName() + " successful");
            }

            @Override
            public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
            }

            @Override
            public void transferStarted(TransferEvent transferEvent) {
                LOGGER.debug("Downloading " + transferEvent.getResource().getName());
            }
        };

        AuthenticationInfo info = null;
        if (username != null && password != null) {
            info = new AuthenticationInfo();
            info.setUserName(username);
            info.setPassword(password);
        }
        ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(httpWagon, listener, info, null);
        Date lastUpdateLocalRepo = indexingContext.getTimestamp();
        IndexUpdateRequest updateRequest = new IndexUpdateRequest(indexingContext, resourceFetcher);
        IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
        if (updateResult.isFullUpdate()) {
            LOGGER.info("Received a full update.");
        } else if (updateResult.getTimestamp() == null) {
            LOGGER.info("No update needed, index is up to date.");
        } else {
            LOGGER.info("Received an incremental update starting from {}." , updateResult.getTimestamp());
        }
    }

}

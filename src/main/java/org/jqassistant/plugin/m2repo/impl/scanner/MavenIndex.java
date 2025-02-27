package org.jqassistant.plugin.m2repo.impl.scanner;

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
import org.apache.maven.index.incremental.DefaultIncrementalHandler;
import org.apache.maven.index.updater.*;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.apache.maven.wagon.providers.http.HttpWagon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.emptyList;

/**
 * This class downloads and updates the remote maven index.
 *
 * @author pherklotz
 */
public class MavenIndex implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenIndex.class);

    private IndexingContext indexingContext;

    private Indexer indexer;

    private final String password;

    private final String username;

    /**
     * Constructs a new object.
     *
     * @param repoUrl
     *            the repository url
     * @param repositoryDirectory
     *            the directory containing the local repository.
     * @param username
     *            the username.
     * @param password
     *             the password.
     * @throws IOException
     *             error during index creation/update
     */
    public MavenIndex(URL repoUrl, File repositoryDirectory, String username, String password) throws IOException {
        File indexDirectory = new File(repositoryDirectory, ".index");
        this.username = username;
        this.password = password;
        try {
            createIndexingContext(repoUrl, repositoryDirectory, indexDirectory);
        } catch (IllegalArgumentException e) {
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
     */
    private void createIndexingContext(URL repoUrl, File repositoryDirectory, File indexDirectory)
            throws IllegalArgumentException, IOException {
        DefaultSearchEngine searchEngine = new DefaultSearchEngine();
        DefaultIndexerEngine indexerEngine = new DefaultIndexerEngine();
        DefaultQueryCreator queryCreator = new DefaultQueryCreator();
        indexer = new DefaultIndexer(searchEngine, indexerEngine, queryCreator);

        // Files where local cache is (if any) and Lucene Index should be located
        String repoSuffix = repoUrl.getHost();
        File localIndexDir = new File(indexDirectory, "repo-index");
        // Creators we want to use (search for fields it defines)
        List<IndexCreator> indexers = new ArrayList<>();
        indexers.add( new MinimalArtifactInfoIndexCreator());
        indexers.add(new JarFileContentsIndexCreator());
        indexers.add(new MavenPluginArtifactInfoIndexCreator());
        indexers.add(new MavenArchetypeArtifactInfoIndexCreator());

        // Create context for central repository index
        indexingContext = indexer.createIndexingContext("jqa-cxt-" + repoSuffix, "jqa-repo-id-" + repoSuffix, repositoryDirectory, localIndexDir,
                repoUrl.toString(), null, true, true, indexers);
    }

    @Override
    public void close() throws IOException {
        indexer.closeIndexingContext(indexingContext, false);
    }

    public ArtifactSearchResult getArtifactsSince(final Date startDate) throws IOException {
        LOGGER.info("Executing query for artifacts that have been updated since {}.", startDate);
        final long startDateMillis = startDate.getTime();
        // find only maven artifact documents
        Query query = indexer.constructQuery(MAVEN.GROUP_ID, new SourcedSearchExpression(Field.NOT_PRESENT));
        IteratorSearchRequest request = new IteratorSearchRequest(query, Collections.singletonList(indexingContext),
                (ctx, ai) -> startDateMillis < ai.getLastModified());
        IteratorSearchResponse artifactInfos = indexer.searchIterator(request);
        LOGGER.info("Artifact query returned {} hits (total).", artifactInfos.getTotalHitsCount());
        return new ArtifactSearchResult(artifactInfos, artifactInfos.getTotalHitsCount());
    }

    /**
     * Update the local index.
     *
     * @throws IOException
     *      When a component could not be looked up.
     */
    public void updateIndex() throws IOException {
        if (indexingContext.getTimestamp() != null) {
            LOGGER.info("Current Maven index timestamp: {}", indexingContext.getTimestamp());
        }
        DefaultIncrementalHandler incrementalHandler = new DefaultIncrementalHandler();
        IndexUpdater indexUpdater =new DefaultIndexUpdater(incrementalHandler, emptyList());
        HttpWagon httpWagon = new HttpWagon();

        TransferListener listener = new AbstractTransferListener() {
            @Override
            public void transferStarted(TransferEvent transferEvent) {
                LOGGER.info("Downloading {}", transferEvent.getResource()
                    .getName());
            }

            @Override
            public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
                LOGGER.debug("Received {} bytes", length);
            }

            @Override
            public void transferCompleted(TransferEvent transferEvent) {
                LOGGER.info("Finished download of {}", transferEvent.getResource()
                    .getName());
            }
        };

        AuthenticationInfo info = null;
        if (username != null && password != null) {
            info = new AuthenticationInfo();
            info.setUserName(username);
            info.setPassword(password);
        }
        LOGGER.info("Updating repository index, this may take a while...");
        ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(httpWagon, listener, info, null);
        IndexUpdateRequest updateRequest = new IndexUpdateRequest(indexingContext, resourceFetcher);
        IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
        if (updateResult.isFullUpdate()) {
            LOGGER.info("Received a full update.");
        } else if (updateResult.getTimestamp() == null) {
            LOGGER.info("No update needed, index is up to date.");
        } else {
            LOGGER.info("Received an incremental update.");
        }
        LOGGER.info("Updated Maven index timestamp: {}", indexingContext.getTimestamp());
    }

}
